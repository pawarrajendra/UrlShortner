"""Disposable k6 matrix; saves actual evidence and never touches the development stack."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
CASES = [('one-no-cache',1,False,False,False), ('two-no-cache',2,False,False,False),
         ('two-warm-cache',2,True,True,False), ('hot-key',2,True,True,True),
         ('cold-cache',2,True,False,False), ('redis-outage',2,True,False,False),
         ('slow-analytics',2,True,True,False)]


def run_matrix():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', default=','.join(c[0] for c in CASES))
    parser.add_argument('--duration', type=int, default=60)
    parser.add_argument('--rate', type=int, default=100)
    parser.add_argument('--output', type=Path, default=ROOT/'docs/performance/phase6-run')
    parser.add_argument('--keep-awake', action='store_true', help='On macOS, inhibit idle sleep only while this process runs')
    parser.add_argument('--image', help='Reuse an already-built current app image; otherwise build once')
    args=parser.parse_args()
    if not 1<=args.duration<=600 or not 1<=args.rate<=1000:
        parser.error('duration 1..600 and rate 1..1000 are required')
    selected=args.cases.split(',')
    if set(selected)-{c[0] for c in CASES}: parser.error('Unknown case')
    output=args.output.resolve()
    if (output/'report.json').exists(): parser.error('Choose a new output directory; existing evidence is not overwritten')
    output.mkdir(parents=True,exist_ok=True)
    if args.keep_awake and shutil.which('caffeinate'):
        subprocess.Popen(['caffeinate','-i','-w',str(os.getpid())],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    project='urlshortener-p6-'+uuid.uuid4().hex[:10]
    image=args.image or project+':test'
    report={'project':project,'startedAt':datetime.now(timezone.utc).isoformat(),
        'host':{'platform':platform.platform(),'architecture':platform.machine()},
        'settings':{'durationSeconds':args.duration,'rate':args.rate,'datasetLinks':10000,
                    'keepAwakeRequested':args.keep_awake,'k6Image':'grafana/k6:1.3.0','appImage':image,'appCpuEach':1,'appMemoryEach':'512m',
                    'heap':'128m..256m','pgCpu':1,'pgMemory':'512m','analyticsWorkersEach':2,
                    'analyticsQueueEach':1000,'analyticsMaxWait':'5s','poolEach':8,
                    'fallbackEach':4,'maintenanceDuringLoad':False,
                    'workload':'GET, deterministic sequential cycling over 10000 codes; hot-key uses code zero',
                    'warmup':'100 HEAD per replica; warm variants seed Redis from one primary SQL snapshot with original PXAT deadlines'},
        'cases':[],'limitations':['One shared host; does not establish linear scaling or HA.',
          'Client p95 includes NGINX/network; server p95 is the maximum approximate per-replica all-status redirect GET timer percentile.',
          'Two-minute percentile window; no pooling of percentiles across replicas.',
          'Warm Redis entries retain original 60-second deadlines and may refresh near the end of a run.',
          'Sampled gauges/resources can miss short spikes; container CPU/memory samples include the generator.']}
    (output/'codes.json').write_text(json.dumps(['L'+format(i,'07x') for i in range(10000)]))
    with tempfile.TemporaryDirectory(prefix=project) as tmp:
        directory=Path(tmp); envfile=directory/'fixture.env'; override=directory/'override.yaml'; conf=directory/'nginx.conf'
        envfile.write_text('POSTGRES_DB=urlshortener\nPOSTGRES_USER=bootstrap\nDATABASE_USERNAME=urlshortener_app\nDATABASE_MIGRATION_USERNAME=urlshortener_migrator\n'+
            '\n'.join(k+'='+secrets.token_hex(24) for k in ['POSTGRES_PASSWORD','DATABASE_PASSWORD','DATABASE_MIGRATION_PASSWORD'])+'\n')
        envfile.chmod(0o600)
        environment=os.environ.copy()
        for line in envfile.read_text().splitlines(): environment.pop(line.split('=',1)[0],None)
        for key in ['PUBLIC_BASE_URL','REDIRECT_CACHE_ENABLED','REDIRECT_CONSISTENCY_MODE','REDIRECT_FALLBACK_CONCURRENCY',
          'ANALYTICS_WORKERS','ANALYTICS_QUEUE_CAPACITY','ANALYTICS_MAX_QUEUE_WAIT','ANALYTICS_CLEANUP_BATCH_SIZE','ANALYTICS_CLEANUP_INTERVAL']:
            environment.pop(key,None)
        command=['docker','compose','--project-name',project,'--env-file',str(envfile),'-f',str(ROOT/'compose.yaml'),'-f',str(override)]
        def run(*parts,input_text=None,check=True):
            return subprocess.run(command+list(parts),cwd=ROOT,env=environment,input=input_text,text=True,capture_output=True,check=check,timeout=600)
        def sql(query):
            return run('exec','-T','postgres','sh','-c','exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At',input_text=query).stdout.strip()
        def redis(*parts,input_text=None):
            return run('exec','-T','redis','redis-cli',*parts,input_text=input_text).stdout.strip()
        def direct(instance,path,measurement=False):
            return run('exec','-T',instance,'curl','-fsS','--max-time','10','http://localhost:'+('9090' if measurement else '8080')+path).stdout
        def metrics(instance): return json.loads(direct(instance,'/actuator/measurement',True))
        def poll(condition,seconds=60):
            until=time.monotonic()+seconds
            while time.monotonic()<until:
                try:
                    if condition(): return
                except (subprocess.CalledProcessError,ValueError): pass
                time.sleep(.2)
            raise RuntimeError('Bounded polling failed')
        def write_config(replicas,cache):
            conf.write_text((ROOT/'infra/nginx/nginx.conf').read_text().replace('server app-2:8080 resolve max_fails=1 fail_timeout=2s;','' if replicas==1 else 'server app-2:8080 resolve max_fails=1 fail_timeout=2s;'))
            override.write_text('services:\n  postgres:\n    cpus: 1.0\n    mem_limit: 512m\n  redis:\n    cpus: 0.5\n    mem_limit: 128m\n'+
                ''.join('  '+app+':\n    image: '+image+'\n    cpus: 1.0\n    mem_limit: 512m\n    command: ["--spring.profiles.active=measure", "--management.server.port=9090", "--management.endpoints.web.exposure.include=health,measurement"]\n    environment:\n      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx256m"\n      REDIRECT_CACHE_ENABLED: "'+str(cache).lower()+'"\n      ANALYTICS_CLEANUP_ENABLED: "false"\n' for app in ['app-1','app-2'])+
                '  migrate:\n    image: '+image+'\n  nginx:\n    cpus: 0.5\n    mem_limit: 128m\n    ports: !override []\n    volumes: !override ["'+str(conf)+':/etc/nginx/nginx.conf:ro"]\n')
        def counter(rows,name,tag=None):
            return sum(r.get('count',0) for r in rows if r['name']==name and (not tag or r['tags'].get(tag[0])==tag[1]))
        def gauge(rows,name): return sum((r.get('value') or 0) for r in rows if r['name']==name)
        def warm_redis():
            # The entire fixture is read from the primary, not a lagging replica. SET PXAT never uses a new relative TTL.
            values=json.loads(sql("SELECT json_build_object('readAt',to_char(statement_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'deadline',floor(extract(epoch FROM statement_timestamp()+interval '60 seconds')*1000)::bigint,'links',(SELECT json_agg(json_build_object('code',code,'id',id,'url',destination_url)) FROM links));"))
            fresh=datetime.fromtimestamp(values['deadline']/1000,timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
            commands=[]
            for link in values['links']:
                body=json.dumps({'version':1,'code':link['code'],'linkId':link['id'],'url':link['url'],'expiresAt':None,'readAt':values['readAt'],'freshUntil':fresh},separators=(',',':'))
                parts=['SET','redirect:v1:'+link['code'],body,'PXAT',str(values['deadline'])]
                commands.append('*5\r\n'+''.join('$'+str(len(x.encode()))+'\r\n'+x+'\r\n' for x in parts))
            redis('--pipe',input_text=''.join(commands))
            assert redis('DBSIZE')=='10000'
            return values['deadline']
        lock=None
        try:
            write_config(2,False)
            if not args.image:
                build=run('build','app-1'); (output/'build.log').write_text(build.stdout+build.stderr)
            info=json.loads(subprocess.run(['docker','info','--format','{{json .}}'],check=True,text=True,capture_output=True).stdout)
            report['docker']={k:info.get(k) for k in ['NCPU','MemTotal','OperatingSystem','Architecture','ServerVersion']}
            report['appImageId']=subprocess.run(['docker','image','inspect','--format','{{.Id}}',image],text=True,check=True,capture_output=True).stdout.strip()
            report['gitHead']=subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,text=True,check=True,capture_output=True).stdout.strip()
            report['sourceState']='uncommitted P6 workspace; image built from current patched source'
            import hashlib
            report['sourceDigests']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'pom.xml',*sorted((ROOT/'src/main').rglob('*.java'))]}
            run('up','-d','postgres','redis','migrate')
            poll(lambda: sql("SELECT count(*) FROM flyway_schema_history WHERE success;")=='3')
            sql("INSERT INTO links SELECT md5(i::text)::uuid,'L'||lpad(to_hex(i),7,'0'),'https://example.com/load/'||i,CURRENT_TIMESTAMP,NULL,NULL FROM generate_series(0,9999) i;")
            report['datasetChecksum']=sql("SELECT md5(string_agg(code||destination_url,',' ORDER BY code)) FROM links;")
            for name,replicas,cache,warm,hot in CASES:
                if name not in selected: continue
                print('Starting measured case:',name,flush=True)
                case_dir=output/name; case_dir.mkdir(exist_ok=True)
                run('stop','nginx','app-1','app-2',check=False)
                sql('TRUNCATE redirect_events;')
                run('start','redis'); poll(lambda: redis('PING')=='PONG'); redis('FLUSHDB')
                write_config(replicas,cache)
                apps=['app-'+str(i) for i in range(1,replicas+1)]
                run('up','-d',*apps)
                for app in apps: poll(lambda app=app: json.loads(direct(app,'/health/ready'))['status']=='UP')
                run('up','-d','--no-deps','--force-recreate','nginx')
                for app in apps:
                    run('exec','-T',app,'sh','-c','i=0; while [ "$i" -lt 100 ]; do curl -fsSI http://localhost:8080/r/L0000000 >/dev/null || exit 1; i=$((i+1)); done')
                if cache and not warm: redis('FLUSHDB')
                before={app:metrics(app) for app in apps}
                deadline=warm_redis() if warm else None
                if name=='redis-outage': run('stop','redis')
                if name=='slow-analytics':
                    lock=subprocess.Popen(command+['exec','-T','postgres','sh','-c','exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At'],cwd=ROOT,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                    lock.stdin.write("BEGIN; LOCK TABLE redirect_events IN ACCESS EXCLUSIVE MODE; SELECT 'held'; SELECT pg_sleep("+str(args.duration+15)+"); ROLLBACK;\n"); lock.stdin.close()
                    while lock.stdout.readline().strip()!='held':
                        if lock.poll() is not None: raise RuntimeError('Failed to establish slow-analytics lock')
                samples=[]; failures=[]; stopped=threading.Event()
                def sample_loop():
                    while not stopped.is_set():
                        try:
                            rows={app:metrics(app) for app in apps}
                            container_ids=run('ps','-q').stdout.split()
                            alive=subprocess.run(['docker','inspect','--format','{{.State.Running}}',project+'-k6-'+name],text=True,capture_output=True)
                            if alive.returncode==0 and alive.stdout.strip()=='true': container_ids.append(project+'-k6-'+name)
                            resources=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}',*container_ids],text=True,check=False,capture_output=True,timeout=10)
                            if not resources.stdout.strip():
                                resources=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}',*run('ps','-q').stdout.split()],text=True,check=True,capture_output=True,timeout=10)
                            if not resources.stdout.strip(): raise RuntimeError('Container resource sample unavailable')
                            db=sql("SELECT json_build_object('connections',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()),'active',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active'),'database',(SELECT row_to_json(d) FROM pg_stat_database d WHERE datname=current_database()));")
                            samples.append({'at':datetime.now(timezone.utc).isoformat(),'metrics':rows,'containers':[json.loads(l) for l in resources.stdout.splitlines()],'database':json.loads(db)})
                        except Exception as error: failures.append(str(error))
                        stopped.wait(2)
                monitor=threading.Thread(target=sample_loop,daemon=True); monitor.start()
                k6=['docker','run','--rm','--name',project+'-k6-'+name,'--network',project+'_default','--cpus','1','--memory','512m','--user',str(os.getuid())+':'+str(os.getgid()),'-v',str(ROOT/'scripts/load')+':/work:ro','-v',str(case_dir)+':/out','-v',str(output/'codes.json')+':/data/codes.json:ro','-e','RATE='+str(args.rate),'-e','DURATION_SECONDS='+str(args.duration),'-e','HOT_KEY='+str(hot).lower(),'-e','DEGRADED='+str(name in ['redis-outage','slow-analytics']).lower(),'grafana/k6:1.3.0','run','/work/redirects.js']
                result=subprocess.run(k6,cwd=ROOT,env=environment,text=True,capture_output=True,timeout=args.duration+90)
                stopped.set(); monitor.join(15)
                (case_dir/'k6.log').write_text(result.stdout+result.stderr)
                if lock:
                    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='psql' AND query LIKE 'SELECT pg_sleep(%';")
                    lock.wait(15); lock=None
                if name=='redis-outage': run('start','redis'); poll(lambda: redis('PING')=='PONG')
                poll(lambda: all(gauge(metrics(app),'analytics.queue.depth')==0 and gauge(metrics(app),'analytics.workers.active')==0 for app in apps),30)
                after={app:metrics(app) for app in apps}
                persisted=int(sql('SELECT count(*) FROM redirect_events;'))
                if not (case_dir/'k6-summary.json').exists(): raise RuntimeError('k6 produced no summary; inspect '+str(case_dir/'k6.log'))
                summary=json.loads((case_dir/'k6-summary.json').read_text())
                values=summary['metrics']; requests=values['http_reqs']['values']['count']; successes=values.get('redirect_successes',{}).get('values',{}).get('count',0)
                def diff(meter,tag=None): return sum(counter(after[a],meter,tag)-counter(before[a],meter,tag) for a in apps)
                losses={reason:diff('analytics.events.lost',('reason',reason)) for reason in ['queue_full','queue_age','admission_closed','shutdown_pending']}
                outcomes={outcome:diff('analytics.events.failure',('outcome',outcome)) for outcome in ['write_failed','commit_unknown','recorder_failed']}
                server=[r.get('p95Seconds') for rows in after.values() for r in rows if r['name']=='redirect.server.duration' and r.get('p95Seconds') is not None]
                hits=diff('redirect.cache.hit'); misses=diff('redirect.cache.miss')
                reconciled=(successes==persisted==diff('analytics.events.committed') and sum(losses.values())+sum(outcomes.values())==0)
                elapsed=summary['state']['testRunDurationMs']/1000
                valid_window=args.duration-.1<=elapsed<=args.duration+12
                case={'elapsedSeconds':elapsed,'measurementWindowValid':valid_window,'name':name,'replicas':replicas,'cache':cache,'warmDeadlineUnixMs':deadline,'durationSeconds':args.duration,'requests':requests,'successes':successes,
                    'clientP95Ms':values['redirect_latency_ms']['values']['p(95)'],'serverP95MaxMs':max(server)*1000 if server else None,
                    'unexpectedErrorRate':values['http_req_failed']['values']['rate'],'droppedIterations':values.get('dropped_iterations',{}).get('values',{}).get('count',0),
                    'instanceDistribution':{app:values.get('served_by{instance:'+app+'}',{}).get('values',{}).get('count',0) for app in ['app-1','app-2']},
                    'mappingQueries':diff('redirect.mapping.queries'),'cacheHits':hits,'cacheMisses':misses,'cacheHitRatio':hits/(hits+misses) if hits+misses else None,
                    'accepted':diff('analytics.events.accepted'),'committed':diff('analytics.events.committed'),'persisted':persisted,
                    'persistedPerSecond':persisted/args.duration,'losses':losses,'failureOutcomes':outcomes,'reconciledWithoutLoss':reconciled,
                    'k6ExitCode':result.returncode,'sampleFailures':failures,'samples':len(samples),
                    'measuredHealthyTargetPassed':valid_window and result.returncode==0 and bool(server) and max(server)*1000<150 and reconciled and requests>=args.rate*args.duration*.99,
                    'degradedVariant':name in ['redis-outage','slow-analytics']}
                (case_dir/'metrics-before.json').write_text(json.dumps(before,indent=2)); (case_dir/'metrics-after.json').write_text(json.dumps(after,indent=2)); (case_dir/'samples.json').write_text(json.dumps(samples,indent=2))
                report['cases'].append(case); (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
                print(json.dumps(case),flush=True)
        finally:
            if lock: lock.terminate(); lock.wait(15)
            teardown=run('down','--volumes','--remove-orphans',check=False)
            report['cleanupSucceeded']=teardown.returncode==0
            report['finishedAt']=datetime.now(timezone.utc).isoformat()
            (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
            print('Disposed only test project:',project,'cleanup:',report['cleanupSucceeded'],flush=True)


if __name__=='__main__': run_matrix()
