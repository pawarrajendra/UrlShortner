"""Disposable full-topology P5 check; never use an existing project's data."""
import ipaddress
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid
from datetime import datetime, timedelta, timezone
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parent.parent

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    project = "urlshortener-p5-" + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=project) as temporary:
        directory = Path(temporary)
        env = directory / "fixture.env"
        env.write_text("POSTGRES_DB=urlshortener\nPOSTGRES_USER=bootstrap\nDATABASE_USERNAME=urlshortener_app\nDATABASE_MIGRATION_USERNAME=urlshortener_migrator\n" + "\n".join(name+"="+secrets.token_hex(24) for name in ["POSTGRES_PASSWORD","DATABASE_PASSWORD","DATABASE_MIGRATION_PASSWORD"]) + "\nPUBLIC_BASE_URL=http://localhost:8080\nREDIRECT_CACHE_ENABLED=true\nREDIRECT_CONSISTENCY_MODE=cached\n")
        env.chmod(0o600)
        override = directory / "override.yaml"
        ids = subprocess.run(["docker","network","ls","-q"],check=True,text=True,capture_output=True).stdout.split()
        networks = json.loads(subprocess.run(["docker","network","inspect",*ids],check=True,text=True,capture_output=True).stdout) if ids else []
        existing = [ipaddress.ip_network(config["Subnet"]) for network in networks for config in (network["IPAM"]["Config"] or []) if "Subnet" in config]
        subnet = next(str(candidate) for number in range(256) for candidate in [ipaddress.ip_network("10.240."+str(number)+".0/24")] if not any(candidate.overlaps(old) for old in existing if old.version == 4))
        override.write_text("services:\n  app-1:\n    image: "+project+":test\n  app-2:\n    image: "+project+":test\n  migrate:\n    image: "+project+":test\n  nginx:\n    ports: !override ['127.0.0.1::8080']\nnetworks:\n  default:\n    ipam:\n      config:\n        - subnet: "+subnet+"\n")
        environment = os.environ.copy()
        for line in env.read_text().splitlines():
            environment.pop(line.split("=",1)[0],None)
        for name in ["ANALYTICS_WORKERS","ANALYTICS_QUEUE_CAPACITY","ANALYTICS_MAX_QUEUE_WAIT","ANALYTICS_CLEANUP_BATCH_SIZE","ANALYTICS_CLEANUP_INTERVAL","REDIRECT_FALLBACK_CONCURRENCY"]:
            environment.pop(name,None)
        command = ["docker","compose","--project-name",project,"--env-file",str(env),"-f",str(ROOT/"compose.yaml"),"-f",str(override)]
        def run(*args, capture=True, input_text=None):
            return subprocess.run(command+list(args),cwd=ROOT,env=environment,text=True,input=input_text,capture_output=capture,check=True,timeout=600)
        def poll(condition, seconds=30):
            deadline=time.monotonic()+seconds
            while time.monotonic()<deadline:
                try:
                    if condition():
                        return
                except (subprocess.CalledProcessError,urllib.error.URLError,ConnectionError):
                    pass
                time.sleep(0.2)
            raise AssertionError("Bounded topology polling timed out")
        opener=urllib.request.build_opener(NoRedirect())
        origin=None
        def ingress(method,route,body=None):
            request=urllib.request.Request(origin+route,data=None if body is None else json.dumps(body).encode(),method=method)
            if body is not None:
                request.add_header("Content-Type","application/json")
            try:
                response=opener.open(request,timeout=6)
            except urllib.error.HTTPError as error:
                response=error
            with response:
                payload=response.read().decode()
                data=(json.loads(payload) if "application/json" in response.headers.get("Content-Type","") else payload) if payload.strip() else None
                return response.status,data,response.headers
        runner="app-1"
        def direct(instance,method,route,body=None):
            curl='exec curl -sS -D /tmp/p5-headers -w "\\n%{http_code}" '
            curl+=('-I -o /dev/null ' if method=="HEAD" else '-X '+method+' ')
            if body is not None:
                curl+='-H "Content-Type: application/json" --data-binary @- '
            curl+='"$1"'
            result=run("exec","-T",runner,"sh","-c",curl,"fixture","http://"+instance+":8080"+route,input_text=None if body is None else json.dumps(body))
            payload,status=result.stdout.rsplit("\n",1)
            headers=run("exec","-T",runner,"cat","/tmp/p5-headers").stdout
            return int(status),json.loads(payload) if payload.strip() else None,headers
        def redis(*args):
            return run("exec","-T","redis","redis-cli",*args).stdout.strip()
        try:
            run("build","app-1",capture=False)
            run("up","-d",capture=False)
            origin="http://"+run("port","nginx","8080").stdout.strip()
            poll(lambda: ingress("GET","/health/ready")[0]==200)
            assert direct("app-1","GET","/health/ready")[0]==200
            assert direct("app-2","GET","/health/ready")[0]==200
            status,link,_=direct("app-1","POST","/api/v1/links",{"url":"https://example.com/topology"})
            assert status==201
            code=link["code"]
            assert direct("app-2","GET","/r/"+code)[0]==302
            poll(lambda: direct("app-1","GET","/api/v1/links/"+code+"/stats")[1]["observedRedirects"]==1 and direct("app-2","GET","/api/v1/links/"+code+"/stats")[1]["observedRedirects"]==1)
            def stable_warm_cache():
                before=redis("PEXPIRETIME","redirect:v1:"+code)
                for instance in ("app-1","app-2","app-1","app-2"):
                    assert direct(instance,"HEAD","/r/"+code)[0]==302
                return int(before)>0 and redis("PEXPIRETIME","redirect:v1:"+code)==before
            poll(stable_warm_cache)
            deadline=redis("PEXPIRETIME","redirect:v1:"+code)
            instances=set()
            for _ in range(20):
                status,_,headers=ingress("HEAD","/r/"+code)
                assert status==302
                instances.add(headers.get("X-App-Instance"))
                assert headers.get("Cache-Control")=="no-store"
            assert instances=={"app-1","app-2"},instances
            assert redis("PEXPIRETIME","redirect:v1:"+code)==deadline
            assert run("exec","-T","app-1","printenv","ANALYTICS_CLEANUP_ENABLED").stdout.strip()=="true"
            assert run("exec","-T","app-2","printenv","ANALYTICS_CLEANUP_ENABLED").stdout.strip()=="false"
            print("PASS: app-1 creation, app-2 resolution, stats through both, NGINX distribution/no-store, fixed cache deadline and singleton cleanup.",flush=True)
            for failed,survivor in [("app-2","app-1"),("app-1","app-2")]:
                run("stop",failed,capture=False)
                runner=survivor
                started=time.monotonic()
                transient=[]
                def stable_survivor():
                    for _ in range(5):
                        status,_,headers=ingress("HEAD","/r/"+code)
                        if status!=302 or headers.get("X-App-Instance")!=survivor:
                            transient.append((status,headers.get("X-App-Instance")))
                            return False
                    return True
                poll(stable_survivor)
                print("Replica "+failed+" stopped: stable survivor in %.3fs; transient observations %s" % (time.monotonic()-started,transient),flush=True)
                run("start",failed,capture=False)
                poll(lambda: direct(failed,"GET","/health/ready")[0]==200)
            runner="app-1"
            print("PASS: stop either replica independently; NGINX serves surviving app and recovers restarted apps.",flush=True)
            run("stop","redis",capture=False)
            for _ in range(8):
                assert ingress("HEAD","/r/"+code)[0]==302
            assert ingress("GET","/health/ready")[0]==200
            run("start","redis",capture=False)
            poll(lambda: redis("PING")=="PONG")
            poll(lambda: direct("app-1","HEAD","/r/"+code)[0]==302 and redis("EXISTS","redirect:v1:"+code)=="1")
            print("PASS: Redis outage keeps bounded PostgreSQL redirects/readiness; disposable cache refills after restart.",flush=True)
            status,expiring,_=ingress("POST","/api/v1/links",{"url":"https://example.com/expiring-topology","expiresAt":(datetime.now(timezone.utc)+timedelta(seconds=4)).isoformat(timespec="microseconds").replace("+00:00","Z")})
            assert status==201 and ingress("HEAD","/r/"+expiring["code"])[0]==302
            poll(lambda: ingress("HEAD","/r/"+expiring["code"])[0]==410,10)
            assert ingress("DELETE","/api/v1/links/"+code)[0]==204
            assert ingress("HEAD","/r/"+code)[0]==410
            # Pin a different address while replacing app-2, proving dynamic DNS rather than IP reuse.
            old=run("ps","-q","app-2").stdout.strip()
            info=json.loads(subprocess.run(["docker","inspect",old],check=True,text=True,capture_output=True).stdout)[0]
            address=info["NetworkSettings"]["Networks"][project+"_default"]["IPAddress"]
            run("stop","app-2",capture=False)
            run("rm","-f","app-2",capture=False)
            holder=project+"-address-holder"
            subprocess.run(["docker","run","-d","--name",holder,"--network",project+"_default","--ip",address,"redis:8.2.1-alpine","sleep","120"],check=True,capture_output=True,text=True)
            try:
                run("up","-d","--no-deps","app-2",capture=False)
                poll(lambda: direct("app-2","GET","/health/ready")[0]==200)
                new=run("ps","-q","app-2").stdout.strip()
                current=json.loads(subprocess.run(["docker","inspect",new],check=True,text=True,capture_output=True).stdout)[0]
                assert current["NetworkSettings"]["Networks"][project+"_default"]["IPAddress"]!=address
                run("stop","app-1",capture=False)
                runner="app-2"
                poll(lambda: ingress("GET","/health/ready")[0]==200)
                assert ingress("GET","/health/ready")[2].get("X-App-Instance")=="app-2"
            finally:
                subprocess.run(["docker","rm","-f",holder],check=True,capture_output=True)
            print("PASS: expiry/disable through ingress and replaced app-2 at a different IP recovered without NGINX reload.",flush=True)
        finally:
            run("down","--volumes","--remove-orphans",capture=False)
        print("PASS: isolated Phase 5 topology; its containers/network/volume removed.",flush=True)

if __name__=="__main__":
    main()
