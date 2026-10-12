"""Isolated Phase 1 Compose validation; never use/delete an existing project volume."""

import json
import hashlib
import io
import tarfile
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
BASELINE = "b098f8f87cbd3d64580b4db174bda24af609b1d6"


def main(core=False, brownfield=False, analytics=False):
    prefix = "urlshortener-p4-" if analytics else "urlshortener-p3-" if brownfield else "urlshortener-p2-" if core else "urlshortener-p1-"
    project = prefix + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=prefix) as directory:
        envfile = Path(directory) / "fixture.env"
        envfile.write_text(
            "POSTGRES_DB=urlshortener\nPOSTGRES_USER=bootstrap\n"
            "DATABASE_USERNAME=urlshortener_app\n"
            "DATABASE_MIGRATION_USERNAME=urlshortener_migrator\n"
            + "\n".join(f"{key}={secrets.token_hex(24)}" for key in (
                "POSTGRES_PASSWORD", "DATABASE_PASSWORD",
                "DATABASE_MIGRATION_PASSWORD"))
            + "\nPUBLIC_BASE_URL=http://localhost:8080\n"
        )
        envfile.chmod(0o600)
        override = Path(directory) / "override.yml"
        override.write_text("services:\n  app:\n    ports: !override []\n")
        if brownfield:
            baseline = Path(directory) / "baseline"
            baseline.mkdir()
            archived = subprocess.run(["git", "archive", "--format=tar", ("20b9159ef8a35accfb42c2165141d2f1b49acbe2" if analytics else BASELINE)],
                                      cwd=ROOT, check=True, capture_output=True, timeout=30).stdout
            with tarfile.open(fileobj=io.BytesIO(archived)) as archive:
                for member in archive.getmembers():
                    target = baseline / member.name
                    if member.name.startswith("/") or ".." in Path(member.name).parts:
                        raise RuntimeError("Unsafe baseline archive path")
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    elif member.isfile():
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(archive.extractfile(member).read())
                        target.chmod(member.mode)
                    else:
                        raise RuntimeError("Unexpected baseline archive entry")
            v1 = "src/main/resources/db/migration/V1__core_links_and_idempotency.sql"
            assert (baseline / v1).read_bytes() == (ROOT / v1).read_bytes(), "Applied V1 was modified"
            override.write_text("services:\n  app:\n    ports: !override []\n    build:\n      context: " + str(baseline) + "\n  migrate:\n    build:\n      context: " + str(baseline) + "\n")
        command = ["docker", "compose", "--project-name", project,
                   "--env-file", str(envfile), "-f", str(ROOT / "compose.baseline.yaml"),
                   "-f", str(override)]
        # Do not let inherited environment credentials override disposable fixtures.
        environment = os.environ.copy()
        for line in envfile.read_text().splitlines():
            environment.pop(line.split("=", 1)[0], None)

        for variable in ("ANALYTICS_WORKERS", "ANALYTICS_QUEUE_CAPACITY", "ANALYTICS_MAX_QUEUE_WAIT", "ANALYTICS_CLEANUP_ENABLED", "ANALYTICS_CLEANUP_BATCH_SIZE", "ANALYTICS_CLEANUP_INTERVAL"):
            environment.pop(variable, None)

        def run(*arguments, capture=False, input_text=None):
            return subprocess.run(command + list(arguments), cwd=ROOT,
                                  env=environment, text=True, input=input_text,
                                  capture_output=capture, check=True, timeout=600)

        def health(route):
            result = run("exec", "-T", "app", "curl", "-sS", "-w", "\n%{http_code}",
                         f"http://localhost:8080/health/{route}", capture=True)
            body, status = result.stdout.strip().rsplit("\n", 1)
            return int(status), json.loads(body)

        def wait_ready():
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                try:
                    if health("ready") == (200, {"status": "UP"}):
                        return
                except (subprocess.CalledProcessError, ValueError):
                    pass
                time.sleep(1)
            raise RuntimeError("Isolated app did not become ready before deadline")

        def sql(statement):
            return run("exec", "-T", "postgres", "sh", "-c",
                       'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At',
                       capture=True, input_text=statement).stdout.strip()

        def api(method, route, body=None, key=None):
            curl = 'exec curl -sS -D /tmp/phase2-headers -w "\\n%{http_code}" '
            curl += ('-I -o /dev/null ' if method == "HEAD" else '-X ' + method + ' ')
            if key:
                curl += '-H "Idempotency-Key: phase2-fixture" '
            if body is not None:
                curl += '-H "Content-Type: application/json" --data-binary @- '
            curl += '"$1"'
            result = run("exec", "-T", "app", "sh", "-c", curl, "smoke",
                         "http://localhost:8080" + route, capture=True,
                         input_text=None if body is None else json.dumps(body))
            payload, status = result.stdout.rsplit("\n", 1)
            headers = run("exec", "-T", "app", "cat", "/tmp/phase2-headers", capture=True).stdout
            assert "cache-control: no-store" in headers.lower()
            assert "x-request-id:" in headers.lower()
            return int(status), (json.loads(payload) if payload.strip() else None), headers

        try:
            if brownfield:
                # Run the saved V1/V2 migrator only, then seed legacy rows directly.
                # The current app is started only after its V3 migration succeeds.
                run("up", "-d", "--wait", "postgres")
                run("build", "migrate")
                run("run", "--rm", "migrate")
                assert sql("SELECT count(*) FROM flyway_schema_history;") == ("2" if analytics else "1")
                url = "https://example.com/phase2?a=1&a=2#fragment"
                code, old_disabled_code = "Legacy01", "Legacy02"
                key = "phase2-fixture"
                digest = hashlib.sha256(("8:operator" + str(len(key)) + ":" + key).encode()).hexdigest()
                fingerprint = hashlib.sha256((str(len(url)) + ":" + url + "4:null").encode()).hexdigest()
                link_id = str(uuid.uuid4())
                sql("INSERT INTO links(id,code,destination_url,created_at,disabled_at) VALUES ('" + link_id +
                    "','" + code + "','" + url + "',CURRENT_TIMESTAMP,NULL),('" + str(uuid.uuid4()) +
                    "','" + old_disabled_code + "','https://example.com/brownfield-disabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);" +
                    "INSERT INTO idempotency_records SELECT '" + digest + "','" + fingerprint + "',id,created_at FROM links WHERE code='" + code + "';")
                checkpoint_query = "SELECT id,code,destination_url,created_at,disabled_at FROM links ORDER BY code;"
                checkpoint = sql(checkpoint_query)
                key_checkpoint = sql("SELECT * FROM idempotency_records ORDER BY key_digest;")
                v1_checksum = sql("SELECT checksum FROM flyway_schema_history WHERE version='1';")
                override.write_text("services:\n  app:\n    ports: !override []\n")
            run("up", "--build", "-d")
            wait_ready()
            assert health("live") == (200, {"status": "UP"})
            if core:
                url = "https://example.com/phase2?a=1&a=2#fragment"
                status, original, _ = api("POST", "/api/v1/links", {"url": url}, key=True)
                assert status == 201
                code = original["code"]
                assert original["shortUrl"] == "http://localhost:8080/r/" + code
                assert api("GET", "/api/v1/links/" + code)[1] == original
                for method in ("GET", "HEAD"):
                    status, _, headers = api(method, "/r/" + code)
                    assert status == 302 and "Location: " + url in headers
            if brownfield:
                assert code == "Legacy01"
                assert sql(checkpoint_query) == checkpoint
                assert sql("SELECT * FROM idempotency_records ORDER BY key_digest;") == key_checkpoint
                assert sql("SELECT checksum FROM flyway_schema_history WHERE version='1';") == v1_checksum
                assert sql("SELECT count(*) FROM links WHERE expires_at IS NOT NULL;") == "0"
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
                assert api("GET", "/r/" + old_disabled_code)[0] == 410
                print("PASS: saved " + ("P3/V2" if analytics else "P2/V1") + " migrations/seeded legacy data upgraded in place to current V3; identities, destinations, timestamps, disabled state, key digests/fingerprints and V1 checksum preserved.")
            sql("INSERT INTO links(id,code,destination_url,created_at,disabled_at) VALUES ('" + str(uuid.uuid4()) +
                "','P1Smoke0','https://example.com/phase1',CURRENT_TIMESTAMP,NULL);")
            run("restart", "app", "postgres")
            wait_ready()
            assert sql("SELECT count(*) FROM links WHERE code='P1Smoke0';") == "1"
            if core:
                assert api("GET", "/api/v1/links/" + code)[1] == original
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
            run("stop", "postgres")
            assert health("ready") == (503, {"status": "DOWN"})
            assert health("live") == (200, {"status": "UP"})
            if core:
                status, error, _ = api("GET", "/r/" + code)
                assert status == 503 and error["error"]["code"] == "DEPENDENCY_UNAVAILABLE"
            run("start", "postgres")
            wait_ready()
            run("run", "--rm", "migrate")
            assert sql("SELECT count(*) FROM flyway_schema_history;") == "3"
            assert sql("SELECT count(*) FROM links WHERE code='P1Smoke0';") == "1"
            if core:
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
                assert api("DELETE", "/api/v1/links/" + code)[0] == 204
                assert api("DELETE", "/api/v1/links/" + code)[0] == 204
                status, error, _ = api("GET", "/r/" + code)
                assert status == 410 and error["error"]["code"] == "LINK_INACTIVE"
                assert api("HEAD", "/r/" + code)[:2] == (410, None)
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
                run("restart", "app", "postgres")
                wait_ready()
                assert api("GET", "/r/" + code)[0] == 410
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
                print("PASS: Phase 2 HTTP lifecycle, keyed replay, retained active/disabled state and key across restarts, safe dependency outage/recovery.")
            if analytics:
                status, measured, _ = api("POST", "/api/v1/links", {"url": "https://example.com/phase4-stats"})
                assert status == 201
                route = "/api/v1/links/" + measured["code"] + "/stats"
                assert api("GET", route)[1]["observedRedirects"] == 0
                assert api("HEAD", "/r/" + measured["code"])[0] == 302
                assert api("GET", "/r/" + measured["code"])[0] == 302
                deadline = time.monotonic() + 10
                while True:
                    status, stats, _ = api("GET", route)
                    assert status == 200
                    if stats["observedRedirects"] == 1:
                        break
                    assert time.monotonic() < deadline, "Analytics did not commit within polling deadline"
                    time.sleep(0.1)
                assert stats["observedRedirects"] == sum(day["observedRedirects"] for day in stats["daily"])
                assert stats["recordingMode"] == "best-effort-async" and stats["eventuallyConsistent"] is True
                assert api("GET", route + "?from=2026-10-01")[0] == 400
                run("restart", "app", "postgres")
                wait_ready()
                assert api("GET", route)[1]["observedRedirects"] == 1
                print("PASS: stats, GET-only analytics, eventual commitment, bucket totals and committed-event persistence across app/PostgreSQL restart.")
            if brownfield:
                expiry = (datetime.now(timezone.utc) + timedelta(seconds=4)).isoformat(timespec="microseconds").replace("+00:00", "Z")
                status, expiring, _ = api("POST", "/api/v1/links", {"url": "https://example.com/expiring", "expiresAt": expiry})
                assert status == 201
                assert datetime.fromisoformat(expiring["expiresAt"].replace("Z", "+00:00")) == datetime.fromisoformat(expiry.replace("Z", "+00:00"))
                assert api("GET", "/r/" + expiring["code"])[0] == 302
                deadline = time.monotonic() + 15
                while True:
                    state = api("GET", "/r/" + expiring["code"])[0]
                    if state == 410:
                        break
                    assert state == 302 and time.monotonic() < deadline, "Expiry did not transition within deadline"
                    time.sleep(0.25)
                assert api("HEAD", "/r/" + expiring["code"])[:2] == (410, None)
                assert api("POST", "/api/v1/links", {"url": "https://example.com/past", "expiresAt": "2020-01-01T00:00:00Z"})[0] == 400
                run("restart", "app", "postgres")
                wait_ready()
                assert api("GET", "/r/" + expiring["code"])[0] == 410
                assert api("POST", "/api/v1/links", {"url": url}, key=True)[1] == original
                assert api("GET", "/r/" + old_disabled_code)[0] == 410
                print("PASS: expiry creation/GET/HEAD/past rejection and expired/disabled/key state persisted through retained restart.")
            print("PASS: isolated Compose startup, migration, readiness/liveness, retained restart, DB outage/recovery and non-destructive migration replay.")
        finally:
            # Only this freshly generated test project is eligible for volume deletion.
            run("down", "--volumes", "--remove-orphans")


if __name__ == "__main__":
    main()
