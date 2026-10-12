"""Replay committed source in a clean archive and an owned disposable Compose project.

Uses the host's documented Java 21/Docker prerequisites and Maven download cache;
never copies .env, target, or untracked files, and never touches developer volumes.
"""
import argparse
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', default='HEAD', help='Committed source revision to export')
    parser.add_argument('--output', default='target/phase7-handoff')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    revision = subprocess.check_output(['git', 'rev-parse', args.revision], cwd=ROOT, text=True).strip()
    project = 'urlshortener-p7-' + uuid.uuid4().hex[:10]
    report = {'sourceRevision': revision, 'project': project, 'startedAt': datetime.now(timezone.utc).isoformat(), 'checks': [], 'passed': False, 'cleanup': False}
    def record(message):
        report['checks'].append(message)
        print('PASS: ' + message, flush=True)
    with tempfile.TemporaryDirectory(prefix=project) as temporary:
        checkout = Path(temporary) / 'source'
        checkout.mkdir()
        archive = subprocess.check_output(['git', 'archive', revision], cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
            stream.extractall(checkout, filter='data')
        assert not (checkout / '.env').exists() and not (checkout / 'target').exists()
        environment = os.environ.copy()
        # Do not inherit a developer's application configuration or Compose settings.
        for key in list(environment):
            if key.startswith(('DATABASE_', 'POSTGRES_', 'ANALYTICS_', 'REDIRECT_', 'REDIS_', 'COMPOSE_', 'SPRING_')) or key in ('PUBLIC_BASE_URL', 'APP_INSTANCE_ID'):
                environment.pop(key)
        env = Path(temporary) / 'fixture.env'
        content = (checkout / '.env.example').read_text()
        for placeholder in ('replace-bootstrap-password', 'replace-runtime-password', 'replace-migration-password'):
            content = content.replace(placeholder, secrets.token_hex(24))
        env.write_text(content)
        env.chmod(0o600)
        override = Path(temporary) / 'override.yaml'
        override.write_text('services:\n  app-1:\n    image: '+project+':test\n  app-2:\n    image: '+project+':test\n  migrate:\n    image: '+project+':test\n  nginx:\n    ports: !override ["127.0.0.1::8080"]\n')
        compose = ['docker', 'compose', '--project-name', project, '--env-file', str(env), '-f', str(checkout/'compose.yaml'), '-f', str(override)]
        def run(*args, check=True):
            return subprocess.run(compose+list(args), cwd=checkout, env=environment, text=True, capture_output=True, check=check, timeout=600)
        def sql(query):
            return run('exec', '-T', 'postgres', 'psql', '-U', 'urlshortener_bootstrap', '-d', 'urlshortener', '-At', '-c', query).stdout.strip()
        opener = urllib.request.build_opener(NoRedirect())
        origin = None
        def request(method, route, body=None, key=None):
            req = urllib.request.Request(origin+route, data=None if body is None else json.dumps(body).encode(), method=method)
            if body is not None:
                req.add_header('Content-Type', 'application/json')
            if key:
                req.add_header('Idempotency-Key', key)
            try:
                response = opener.open(req, timeout=6)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                payload = response.read()
                return response.status, json.loads(payload) if payload and 'application/json' in response.headers.get('Content-Type', '') else payload, response.headers
        def poll(predicate, seconds=65):
            deadline = time.monotonic()+seconds
            while time.monotonic()<deadline:
                try:
                    if predicate():
                        return
                except (urllib.error.URLError, ConnectionError):
                    pass
                time.sleep(0.25)
            raise AssertionError('Bounded polling timed out')
        try:
            with (output/'verify.log').open('w') as log:
                subprocess.run([str(checkout/'mvnw'), '-B', '-ntp', '-Dmaven.repo.local='+str(ROOT/'.m2-cache'), 'clean', 'verify'], cwd=checkout, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
            record('Clean committed-source archive: Maven clean verify passed with shared download cache, no copied build output')
            with (output/'build.log').open('w') as log:
                subprocess.run(compose+['build', 'app-1'], cwd=checkout, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
            report['imageId'] = subprocess.check_output(['docker', 'image', 'inspect', project+':test', '--format', '{{.Id}}'], text=True).strip()
            run('up', '-d')
            origin = 'http://'+run('port', 'nginx', '8080').stdout.strip()
            poll(lambda: request('GET', '/health/ready')[0]==200)
            assert sql('SELECT string_agg(version,\',\' ORDER BY installed_rank) FROM flyway_schema_history WHERE success')=='1,2,3'
            record('Fresh full Compose bootstrap, V1/V2/V3 migration and public readiness passed')
            destination = 'https://example.com/handoff?a=1&a=2#section'
            status, link, _ = request('POST', '/api/v1/links', {'url': destination}, 'handoff-key')
            assert status==201
            code = link['code']
            route = '/api/v1/links/'+code
            status, _, headers = request('GET', '/r/'+code)
            assert status==302 and headers.get('Location')==destination and headers.get('Cache-Control')=='no-store'
            status, body, headers = request('HEAD', '/r/'+code)
            assert status==302 and body==b'' and headers.get('Location')==destination
            poll(lambda: request('GET', route+'/stats')[1]['observedRedirects']==1, 15)
            assert request('GET', route)[1]==link
            assert request('POST', '/api/v1/links', {'url': destination}, 'handoff-key')[:2]==(201, link)
            record('Create, keyed replay, metadata, exact GET/HEAD redirect semantics and one eventual GET event passed')
            assert request('DELETE', route)[0]==204
            poll(lambda: request('HEAD', '/r/'+code)[0]==410)
            assert request('GET', '/r/'+code)[0]==410
            assert request('HEAD', '/r/00000000')[:2]==(404, b'')
            disabled = request('GET', route)[1]
            assert disabled['disabledAt'] is not None
            record('Disable convergence and bodyless HEAD errors passed')
            run('down')  # Deliberately retain this fixture's volume, just like README shutdown.
            run('up', '-d')
            origin = 'http://'+run('port', 'nginx', '8080').stdout.strip()
            poll(lambda: request('GET', '/health/ready')[0]==200)
            assert request('GET', route)[1]==disabled
            assert request('HEAD', '/r/'+code)[0]==410
            assert request('GET', route+'/stats')[1]['observedRedirects']==1
            assert request('POST', '/api/v1/links', {'url': destination}, 'handoff-key')[:2]==(201, link)
            record('Compose down/up with retained volume preserved disabled mapping, original keyed replay and committed event')
            run('stop', 'nginx', 'app-1', 'app-2')
            checksum = sql("SELECT checksum FROM flyway_schema_history WHERE version='1'")
            sql("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'")
            try:
                failed = run('run', '--rm', 'migrate', check=False)
                assert failed.returncode!=0
                assert 'Startup failed' in failed.stdout+failed.stderr
                report['migrationFailureMessage'] = 'Startup failed (details intentionally redacted)'
                blocked = run('up', '-d', '--force-recreate', 'migrate', 'app-1', 'app-2', 'nginx', check=False)
                assert blocked.returncode!=0
                assert 'app-1' not in run('ps', '--status', 'running', '--services').stdout.splitlines()
                assert 'app-2' not in run('ps', '--status', 'running', '--services').stdout.splitlines()
                report['migrationFailureExit'] = failed.returncode
                record('Isolated checksum mismatch caused nonzero migration exit and blocked application startup; stored rows retained')
            finally:
                sql("UPDATE flyway_schema_history SET checksum="+checksum+" WHERE version='1'")
            assert sql('SELECT count(*) FROM links')=='1'
            run('up', '-d', '--force-recreate', 'migrate', 'app-1', 'app-2', 'nginx')
            origin = 'http://'+run('port', 'nginx', '8080').stdout.strip()
            poll(lambda: request('GET', '/health/ready')[0]==200)
            assert request('GET', route)[1]==disabled
            assert request('GET', route+'/stats')[1]['observedRedirects']==1
            record('Fixture history restoration and migration replay recovered without deleting persisted data')
            report['passed'] = True
        finally:
            try:
                run('down', '--volumes', '--remove-orphans')
                report['cleanup'] = True
            finally:
                report['finishedAt'] = datetime.now(timezone.utc).isoformat()
                (output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print('PASS: Handoff replay; only owned fixture resources removed. Evidence: '+str(output), flush=True)

if __name__ == '__main__':
    main()
