# AI-Assisted URL Shortener

Implemented: Java 21 / Spring Boot 4.1.0 application, validated/redacted configuration, UTC clock, PostgreSQL V1/V2/V3 schema, dedicated Flyway migration profile, separate runtime/migration roles, minimal liveness/readiness aliases, Maven Wrapper, blocking formatting/style gates, unit tests, PostgreSQL Testcontainers integration tests, Docker foundation and CI.

Phase 2 implements creation/metadata/disable, optional 24-hour idempotency, and public GET/HEAD redirects. Codes are immutable eight-character Base62 values; PostgreSQL retains mappings after disabling. Phase 3 adds optional expiry without changing existing destinations/codes. Phase 4 adds bounded asynchronous GET events, UTC statistics and retention cleanup. Phase 5 adds shared Redis mappings, bounded fallback and two app replicas behind NGINX. All HTTP APIs are public.

| Route | Access | Behavior |
| --- | --- | --- |
| POST `/api/v1/links` | Public | `201` creation or original keyed replay; body contains `url` and optional `expiresAt` |
| GET `/api/v1/links/{code}` | Public | `200` current metadata, including disabled links |
| DELETE `/api/v1/links/{code}` | Public | `204` irreversible disable, including repeated requests |
| GET/HEAD `/r/{code}` | Public | `302` / Location, missing `404`, disabled/expired `410`; HEAD has no body |
| GET `/api/v1/links/{code}/stats` | Public | Retained GET attempts in UTC buckets; eventual best-effort results |
| GET `/health/live`, `/health/ready` | Public | Minimal process/database/schema status |

All application responses carry a fresh request ID and no-store. Errors use a safe envelope. Cross-origin access is not configured; browser preflight responses do not grant an allowed origin. Other supported redirect methods return `405` with `Allow: GET, HEAD`; malformed HTTP may be rejected by the container. Current API definition: [Phase 5 OpenAPI](docs/openapi-phase5.yaml), also at root [OpenAPI](openapi.yaml). P2–P4 feature snapshots are retained and reconciled with current public access; original contracts remain in their recorded Git revisions.

## Start here

This is an engineer-led URL-shortener prototype built with AI assistance. The assignment does not require an AI agent inside the running service. Read [the requirement checklist](#assignment-requirements-and-review-questions) to see how each requirement is addressed and [the engineering summary](docs/engineering-summary.md) for results, assumptions and limitations. Human final acceptance remains pending.

For a first run, follow [prerequisites](#prerequisites), [local startup](#start-the-local-stack), then [the annotated API walkthrough](#phase-wise-curl-walkthrough). Docker builds Java inside its image, so Java 21 on the host is required for Maven verification rather than Docker-only startup. Maven is supplied by `./mvnw`; do not install a separate version. Shell walkthroughs require Bash, curl and Python 3; contract checks additionally use Ruby with its standard libraries. The clean-source handoff runner needs Git and Python 3.12+.

- [Core API examples](#try-the-core-apis)
- [Full verification](#verify) and [scenario testing](#greenfield-brownfield-ambiguous-and-public-access-scenarios)
- [Failure/load testing](#phase-6-reliability-and-measurement)
- [Troubleshooting](#troubleshooting)
- [Stop safely](#stop-without-deleting-development-data)
- [Clean-source replay and reviewer handoff](docs/handoff/README.md)

## Try the core APIs

Start Compose as described below. No API credential is required.

```bash
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-create-1' \
  --data '{"url":"https://example.com/articles?a=1&a=2#section"}'
# Replace Ab3dE9xQ with the returned code.
curl -i http://localhost:8080/api/v1/links/Ab3dE9xQ
curl -i http://localhost:8080/r/Ab3dE9xQ
curl -I http://localhost:8080/r/Ab3dE9xQ
# GET counts asynchronously; HEAD does not. Poll stats for committed observations.
curl -i http://localhost:8080/api/v1/links/Ab3dE9xQ/stats
# Optional explicit UTC calendar range: [from,to), at most 30 days.
curl -i 'http://localhost:8080/api/v1/links/Ab3dE9xQ/stats?from=2026-10-01&to=2026-10-12'
curl -i -X DELETE http://localhost:8080/api/v1/links/Ab3dE9xQ
```

The app does not fetch destinations; do not add `curl -L` to redirect checks. Creation accepts only UTF-8 JSON, rejects duplicate/unknown fields, and limits body bytes to 16 KiB, including chunked requests. URLs must be absolute HTTP/HTTPS, at most 2,048 code points and 2,048 serialized ASCII characters. Userinfo, controls, private/local literals, local-only names and the configured public hostname are rejected. Unicode paths serialize to ASCII; hosts require ASCII/punycode. DNS/reputation checks are not performed, so a DNS name can later resolve privately or become unreachable.

One optional `Idempotency-Key` must match `[A-Za-z0-9._:-]{1,128}`; repeated/comma-combined headers are invalid. Only digests/fingerprints are stored. Keys have a global namespace. The existing digest prefix is preserved so previously stored creation keys remain replayable. A matching request within `[createdAt, createdAt + 24 hours)` returns the original creation result (including `disabledAt: null`); GET metadata reports the current lifecycle. A different URL within that window returns `409`. At/after 24 hours, a key can create a new link; conditional stale deletion rechecks the old row under PostgreSQL's DELETE lock. Bounded hourly stale-key cleanup is implemented; replay-window enforcement does not depend on cleanup. Unkeyed retries may create duplicates, and lost/uncertain responses must use keyed replay rather than assume rollback.

Code conflicts retry up to five times in fresh transactions; exhaustion returns `503`. Key-conflict/replacement retries are separately capped at five; other database/commit failures return safe dependency errors without generic retries. Counters `links.creation.code.collisions` and `links.creation.code.exhausted` are registered with Micrometer; metrics are not publicly exposed.

## Phase-wise curl walkthrough

With the application running, run either walkthrough. No token or exported credential is required:

```bash
bash scripts/api-curl-walkthrough.sh
# Broader phase-wise walkthrough:
bash scripts/sample-curl.sh
# Optional: different origin or single-app baseline (no NGINX ingress endpoint)
BASE_URL=http://localhost:8080 SKIP_INGRESS_CHECK=1 bash scripts/sample-curl.sh
```

For explicit curl URLs with inline API purpose, expected HTTP status/headers/body, and executable validation after each call, use [api-curl-walkthrough.sh](scripts/api-curl-walkthrough.sh):

```bash
bash scripts/api-curl-walkthrough.sh
```

This version creates two links, validates all current API routes, and keeps the requests directly visible for copying or modifying. It uses the same running application and public API access.

Both scripts print a request log **before every call**, including each polling attempt: UTC send timestamp, check purpose, what the API does, HTTP method, full resolved URL/query, headers and JSON body (or none). [curl-request-log.py](scripts/curl-request-log.py) derives the log from the curl arguments; the explicit walkthrough retains its curl commands. The following status/body checks validate the response. A fixed diagnostic User-Agent makes the displayed request headers reproducible.

To keep requests and responses in one transcript:

```bash
bash scripts/api-curl-walkthrough.sh 2>&1 | tee curl-walkthrough.log
```


Requires Bash, curl and Python 3. [sample-curl.sh](scripts/sample-curl.sh) prints each request and expected status, captures generated codes, and uses unique idempotency keys on every run. It groups checks as Phase 0 scope, Phase 1 health, Phase 2 validation/core/idempotency, Phase 3 expiry, Phase 4 eventual UTC stats, and Phase 5 replica observations/disable convergence. Phases 6–7 identify separate verification/handoff work.

The walkthrough creates three sample links; the core and analytics links are disabled and the third expires. Their mapping rows and creation keys remain according to the application's retention policy. If interrupted, it prints the codes created so far; it does not delete data or stop services. Redirect checks never follow `Location`.

Expiry polling takes about five seconds; stats poll for up to 15 seconds, and cached disabling allows up to 65 seconds plus individual request timeouts. Failure to observe analytics can reflect best-effort loss/lag and should be investigated. Replica headers show which instances served requests; least-connections does not guarantee both will appear in one short run. Redis hit counts, absolute TTLs, SQL reduction and outage behavior require the existing dedicated tests/smoke script, rather than inference from HTTP responses.

## Mapping cache and replicas (Phase 5)

`REDIRECT_CACHE_ENABLED` defaults to false for standalone/baseline runs and true in the full Compose stack. `REDIRECT_CONSISTENCY_MODE=cached` uses Redis; `strict` reads PostgreSQL for every resolution. Set either in `.env`, then recreate the apps with `docker compose up -d --force-recreate app-1 app-2`. Management metadata/stats always read the primary. No sticky sessions or HTTP redirect-response caching are used.

Cached mode can briefly resolve a disabled link after DELETE commits. Disable commits first, then best-effort deletes `redirect:v1:{code}`. A delayed old read can refill after that deletion. Every cached value retains PostgreSQL `statement_timestamp()` as `readAt` and `freshUntil=min(readAt+60 seconds, expiresAt)`, conservatively rounded down to milliseconds for Redis `SET PXAT`. Hits do not extend it. Replicas validate the embedded version, identity, destination, times and deadline before use; missing/corrupt/expired entries fall back to the primary. Strict mode avoids this cached disable window, while a request already resolved before disable can still finish. Unknown/missing/inactive/error results are not cached.

Lettuce has 100 ms connect and per-command waits, no disconnected command buffering, a 64-command bound, two I/O and two computation threads. A three-failure breaker bypasses Redis for two seconds, then admits one recovery probe. Connection plus command can consume separate waits; these settings do not guarantee a complete HTTP deadline. A cold connection can fall back before cache warming succeeds. Four simultaneous mapping fallback reads per replica use immediate admission; exhausted admission returns controlled 503. Same-key cached misses coalesce per replica, with at most 32 followers and one-second waits. There is no cross-replica fill lock or reserved request connection. Servlet threads/connections/accept queue are also bounded. None of these resource bounds is a client quota or 429 policy.

`X-App-Instance` is server-configured (`app-1`/`app-2`) and appears in request logs. Two apps can use 16 pooled runtime connections and four concurrent event transactions. Recalculate the database budget before adding replicas. Cache metrics count hits/misses/corruption/staleness, failures by operation, breaker bypass, expired fills, durations, mapping queries and fallback/coalesced admission. No public metrics exporter is enabled yet.

NGINX 1.28.0 uses least connections, a shared upstream zone, Docker DNS `resolve` with a two-second validity and one-second resolution timeout, and passive `max_fails=1/fail_timeout=2s`. It overwrites inbound forwarding/instance headers; apps ignore forwarded metadata and use configured public URLs. It does not log destination/query strings. GET/HEAD connection-error retries can create additional observed attempts; non-idempotent writes are not explicitly enabled for replay after sending. Recovery can include transient errors and is not zero-loss failover. `/health/ingress` confirms NGINX itself; `/health/ready` reaches one app, not an aggregate cluster health check.

The disposable P5 check proves distribution, cross-app committed stats, fixed cache expiry, each app stopping, Redis stopping/recovering and dynamic DNS recovery after a replacement app obtains a different IP. Real PostgreSQL/Redis tests verify warm mapping-read reduction, strict mode, corrupt values/eviction/restart, paused-read refill, failed invalidation and expiry. For fast convergence tests, application clocks are deliberately advanced by 58 seconds while preserving the actual PostgreSQL read time and Redis deadline; this accelerates expiry checks without claiming a 60-second wall-clock load test. Runtime clocks must be synchronized; future `readAt` beyond one second is rejected, and real skew tolerance remains unmeasured. Phase 6 adds local measured throughput/latency, known Maven-advisory checks and failure evidence; production availability/failover remain later work.

## Analytics (Phase 4)

```bash
curl -s http://localhost:8080/api/v1/links/REPLACE_CODE/stats
curl -s 'http://localhost:8080/api/v1/links/REPLACE_CODE/stats?from=2026-10-05&to=2026-10-12'
```

One active GET resolution submits one observed attempt; HEAD, unsupported methods, missing, disabled, expired and failed lookups submit none. Bots, prefetch and retries can count. This does not measure unique people or successful browser delivery. Only event UUID, link UUID and the resolution timestamp are stored; no visitor identity or destination is copied into events.

Two named workers and 1,000 queued tasks per app use throwing rejection, caught before returning the valid redirect. A worker opens its own transaction. Events waiting more than five seconds are dropped using monotonic elapsed time; exactly five seconds is accepted. The `.env.example` `ANALYTICS_*` settings tune workers/queue/age and maintenance, with startup bounds (workers 1–8, queue 1–100,000, wait >0 to 1 minute, cleanup batch 1–10,000, interval 1 second to 1 day). Worker work shares the eight-connection request pool; request connections are not reserved.

Stats return `observedRedirects`, ordered `daily` buckets, `availableFrom`, `asOf`, `partialRetention`, `recordingMode: "best-effort-async"` and `eventuallyConsistent: true`. Immediate results can lag. The default covers seven UTC calendar days including today; explicit dates select `[from,to)` with a maximum 30-day span. Both dates must be supplied once; unknown/duplicate/malformed parameters yield `400 / INVALID_DATE_RANGE`. Empty and future buckets are zero. Query end is capped at `asOf`, which does not certify complete ingestion. Counts at the exact 30-day cutoff remain available, while older events are excluded even before cleanup.

Hourly maintenance deletes at most 1,000 expired events and 1,000 stale idempotency records per run in separate transactions. Deletes retain rows at the exact cutoff and recheck identity, observed creation time and current expiry to protect recreated keys. Mapping rows are retained. Heavy backlogs can require tuning batch/interval: query retention is strict, physical removal can lag. Cleanup runs only on app-1 in the full topology.

Shutdown starts one ten-second HTTP-plus-analytics budget at context close. Spring's graceful HTTP phase drains in-flight requests before the analytics lifecycle closes admission, stops maintenance, drains accepted work using the remaining budget, interrupts/discards work at expiry, and explicitly closes Hikari last. Compose allows 15 seconds for this plus process teardown. The lifecycle phase is pinned to Boot 4.1 and exercised by a real in-flight HTTP test. Interruption cannot certify an in-flight commit outcome. A crash/SIGKILL can lose memory events without emitting a final metric; committed events persist. The hard-stop integration demonstration uses a test-only paused subprocess, with no production pause endpoint.

Micrometer records `analytics.events.accepted`, `.committed`, `.lost{reason}`, `.failure{outcome}`, queue depth/oldest age/wait, active workers, successful delivery lag, forced shutdown, and cleanup deletion/failure. Unknown commit outcomes have their own counter and are not retried. Metrics are available to application instrumentation/tests; a public metrics endpoint/exporter is not enabled in P4. Phase 6 records local throughput, delivery lag and database/resource samples in [performance evidence](docs/performance/README.md); production capacity remains unproven.

## Optional expiration (Phase 3)

Creation accepts `expiresAt` as null/absent (non-expiring) or an offset timestamp, for example `2026-12-31T23:59:59.123456Z`. Use `YYYY-MM-DDTHH:mm:ss[.fraction]Z` or a numeric `±HH:mm` offset; at most six fractional digits and UTC years 0001–9999. Leap seconds, missing offsets, malformed values, wrong types and excess precision are rejected without rounding. A new expiry must be strictly after the single captured microsecond creation instant. Very short links may already be expired when their creation response reaches a client.

Metadata includes nullable `expiresAt`, always normalized to UTC. Responses include this field for old links too; clients enforcing the exact P2 schema need the P3 contract. Both GET and HEAD redirect before expiry and return `410 / LINK_EXPIRED` at/after it; HEAD errors have no body. A disabled expired link uses `LINK_INACTIVE` precedence. Expiry retains the mapping/code; it does not delete records or extend idempotency. Matching replay after expiry still returns the original 201 result; changing expiry under a live key returns 409. Equivalent offset representations share a fingerprint. P2 null-expiry fingerprints are preserved byte-for-byte.

V2 is additive: `ALTER TABLE` adds nullable `expires_at` and `expires_at IS NULL OR expires_at > created_at`; no V1 rewrite, backfill, reset or extra index. Existing V1 links remain non-expiring and retain identities, destinations, timestamps, disabling and keys. Upgrade an existing local stack with `docker compose build app-1` followed by `docker compose up -d` using the same project/volume and original role credentials. Review [brownfield evidence](docs/scenarios/brownfield.md) first. Readiness stays DOWN on V1-only until the migration job completes all pending V2/V3 migrations. Older P2 code does not enforce expiry and must not serve after expiring links are created; this is a coordinated local upgrade, not a rolling downgrade guarantee. Preserve storage and investigate failed migrations; do not delete the volume or edit applied SQL to recover.

## Prerequisites

- Java 21 JDK; ensure Maven and the wrapper use it with `JAVA_HOME`.
- Docker Engine/Desktop with Compose and a running daemon. Testcontainers uses that daemon and cannot silently skip checks when unavailable.
- Network access for the first Maven/image downloads.

macOS example (select your installed Java 21 distribution):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -version
docker version
docker compose version
```

Maven 3.9.16 / wrapper 3.3.4, application dependencies and formatting plugins are pinned in the wrapper/POM. The build compiles to Java 21 bytecode. PostgreSQL image is pinned to `postgres:18.0-alpine`, also used by integration tests; review patch/security updates before deployment. Docker base images use Java 21 tags; digest pinning and dependency/security review remain hardening work, not a completed audit.

## Verify

```bash
./mvnw -B -ntp clean verify
```

`verify` blocks on Spotless, Checkstyle, unit tests and mandatory PostgreSQL and Redis integration tests. Failsafe runs `*IT` tests after executable-JAR packaging. Missing Docker, required failed tests, or no integration tests is a verification failure. Container databases are disposable and isolated from development data. Reports: `target/surefire-reports` and `target/failsafe-reports`.

For quick local iteration only:

```bash
./mvnw spotless:apply
./mvnw test
```

`test` alone is not full verification; it excludes the Failsafe integration stage. Docker image builds skip tests because Docker-backed verification belongs to the explicit verify/CI gate; a successful image build is not test evidence.

## Start the local stack

```bash
cp .env.example .env
# Replace passwords in .env before starting. Do not commit it.
docker compose up --build -d
docker compose ps
curl -i http://localhost:8080/health/live
curl -i http://localhost:8080/health/ready
```

The default stack has NGINX, app-1, app-2, Redis and PostgreSQL plus a one-shot migration job. Only NGINX binds `127.0.0.1:8080`. Both apps/migration use the single image built by app-1. PostgreSQL health gates migration; completed migration and healthy Redis gate apps; both app health checks gate initial ingress startup. Only app-1 schedules cleanup. Redis holds disposable mappings with a 64 MiB limit and allkeys-LRU eviction; PostgreSQL retains durable state. A failed migration prevents apps from starting. Compose gates initial startup; NGINX passively detects connection failures and does not continuously remove live-but-unready apps.

For the cache-disabled single-app baseline, use `docker compose -f compose.baseline.yaml up --build -d`. It exposes app on the same host port; run one topology at a time. To upgrade an existing P4 stack with its original credentials/volume, first stop its old `app` using `docker compose -f compose.baseline.yaml stop app`, then build app-1 and use `docker compose up -d --remove-orphans`. This removes the retired app container while retaining PostgreSQL storage. Do not delete volumes to resolve port or migration problems.

### Configuration reference

Copy `.env.example`; edit it as data, rather than sourcing it as a shell script. Compose supplies JDBC addresses internally. `.env` and download/build output are ignored by Git. Standalone JAR execution uses explicitly exported environment variables, not automatic `.env` loading.

| Setting | Purpose / default |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database name/bootstrap role and first-initialization password; example database is `urlshortener` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Restricted runtime role `urlshortener_app` and a separate password |
| `DATABASE_MIGRATION_USERNAME`, `DATABASE_MIGRATION_PASSWORD` | Schema-owner migration role `urlshortener_migrator` and a separate password; only migration job receives these |
| `PUBLIC_BASE_URL` | Returned short-link origin and self-target rejection hostname; default `http://localhost:8080` |
| `ANALYTICS_WORKERS`, `ANALYTICS_QUEUE_CAPACITY`, `ANALYTICS_MAX_QUEUE_WAIT` | Per-replica bounds: `2`, `1000`, `5s` |
| `ANALYTICS_CLEANUP_BATCH_SIZE`, `ANALYTICS_CLEANUP_INTERVAL` | At most `1000` rows per table per run, every `1h`; full topology explicitly owns cleanup on app-1 |
| `REDIRECT_CACHE_ENABLED`, `REDIRECT_CONSISTENCY_MODE` | Full stack defaults `true`, `cached`; `strict` checks the primary |
| `REDIRECT_FALLBACK_CONCURRENCY` | Concurrent primary fallback resolutions per app; default `4` |

Standalone execution additionally requires `DATABASE_URL=jdbc:postgresql://HOST:PORT/DATABASE`; the migration/runtime role settings differ as shown below. Runtime port is `SERVER_PORT` (default 8080), instance label `APP_INSTANCE_ID`, and cache host/port `REDIS_HOST`/`REDIS_PORT` when enabled. The full Compose file sets internal addresses and replica identities. Local Redis has no authentication/TLS and is unpublished; hosted Redis security is future work. Default deployment exposes health only; private measurement tooling requires the isolated `measure` profile and is not routed by NGINX.

Runtime/migration role names are fixed by `infra/postgres/init/10-roles.sh`: keep `DATABASE_USERNAME=urlshortener_app` and `DATABASE_MIGRATION_USERNAME=urlshortener_migrator`. Change role names only with a reviewed bootstrap/grant change. Use separate passwords. The app never receives migration credentials.

Role bootstrap runs only when the PostgreSQL volume is first initialized. Changing `.env` does not rotate passwords in an existing database. Review explicit role changes instead of deleting the volume. PostgreSQL 18's data volume is mounted at `/var/lib/postgresql`.

Readiness requires database connectivity and exactly the successful V1/V2/V3 migration history; missing/incompatible history returns `503 {"status":"DOWN"}`. Liveness checks process availability independently and returns `200 {"status":"UP"}`. Neither exposes database internals. Built-in `/actuator/health` is also public with health details suppressed; metrics endpoints are not exposed. Responses have a fresh `X-Request-Id` and `Cache-Control: no-store`; inbound correlation IDs are ignored. The runtime role can read migration history but cannot modify it or perform schema DDL/delete retained links.

## Run against an existing isolated PostgreSQL database

Provision roles/schema with the documented bootstrap contract first. Set PostgreSQL URL and separate credentials through local environment configuration. Do not use a development or production database for integration tests.

```bash
# Required environment: DATABASE_URL, DATABASE_MIGRATION_USERNAME/PASSWORD
java -jar target/urlshortener-0.1.0-SNAPSHOT.jar --spring.profiles.active=migrate
# Required environment: DATABASE_URL, DATABASE_USERNAME/PASSWORD
java -jar target/urlshortener-0.1.0-SNAPSHOT.jar
```

Migration uses two pooled connections for Flyway metadata/migration work and no web server, validates existing history, applies pending V1/V2/V3, validates the result and closes before exit. Runtime has eight connections, one-second acquisition/query limits, 500 ms role lock timeout, and driver connection/socket limits. These bounds address different failures and do not establish a hard HTTP deadline. Future replicas must account for all pools.

## Disposable startup/restart/outage checks

```bash
python3 scripts/phase1-smoke.py
python3 scripts/phase2-smoke.py
python3 scripts/phase3-smoke.py
python3 scripts/phase4-smoke.py
python3 scripts/phase5-smoke.py
```

This builds the image, creates a unique Compose project with generated test credentials and no host app port, checks fresh migration and readiness, seeds a fixture, checks app/database restart persistence, tests database outage (ready DOWN/live UP), reruns migration, and tears down only its own disposable containers/volume. The Phase 2 check additionally creates a real link through HTTP and verifies inspection, public GET/HEAD, disabling, replay and preserved link/key/lifecycle state across restarts, plus safe redirect failure during DB outage. The Phase 3 check runs the saved Phase 2 migration job only, seeds active/disabled/keyed rows on V1 through SQL, then upgrades the same isolated volume to the current V3 and verifies unchanged baseline state plus expiry/restart behavior. The Phase 4 check runs the saved P3/V2 migration job, seeds legacy rows through SQL, upgrades that volume to V3 and checks GET-only statistics and committed history across app/database restart. Git history containing the saved baselines is required. Legacy checks now use `compose.baseline.yaml`. The P5 check builds one app image and exercises the full disposable topology. None of these checks reads your `.env` or touches an existing development project. Keep Docker running; the script reports actual success/failure.

## Phase 6 reliability and measurement

The full build runs mandatory real PostgreSQL/Redis checks, including actual pool exhaustion, row-lock timeout, slow-query cancellation, paused database networking, cleanup-owner recovery and a private measurement-port regression. Existing tests retain queue saturation, graceful HTTP/analytics shutdown and hard-stop pending-event loss.

```bash
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache clean verify
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree \
  -DoutputType=json -DoutputFile=target/dependency-tree.json
python3 scripts/dependency-audit.py
python3 scripts/phase6-load.py --keep-awake --output target/phase6-my-run
python3 scripts/phase6-report.py target/phase6-my-run
```

The load harness seeds 10,000 links, builds one app artifact and runs seven comparisons at 100 GET requests/second for 60 seconds each in a disposable stack. It records resource limits, client and all-status server p95, replica distribution, mapping SQL/cache counters, queue/lag/loss metrics and persisted events after drain. No external destinations are fetched. A `measure` profile exposes whitelisted counters/timers on an unpublished management port only in the fixture; normal deployment keeps its existing health-only exposure. Existing report directories are not overwritten. On macOS, `--keep-awake` inhibits idle sleep only while the runner exists, and elapsed-window checks detect interrupted comparisons.

Dependency patch versions are pinned in the POM, with actual before/after OSV evidence. The audit covers resolved Maven artifacts including tests; unavailable advisory queries fail closed. Native container/build-plugin auditing and hosted CI execution remain outside this evidence. See [instructions and limitations](docs/performance/README.md), [source review](docs/performance/source-review.md) and [measured results](docs/performance/phase6-final/results.md).

## Stop without deleting development data

```bash
docker compose down
```

Normal shutdown preserves the named PostgreSQL volume. A volume is not a backup. Avoid `down --volumes` for development state; deleting storage or restoring an older backup falls outside restart guarantees. Hosted TLS, backup/restore, durable analytics and production failover are not implemented here.

See [implementation phases](docs/implementation-plan.md), [contracts](docs/phase0-contracts.md), [schema plan](docs/schema-plan.md), and [actual validation evidence](docs/validation-results.md). Rate limiting remains optional and deferred.

## Greenfield, brownfield, ambiguous and public-access scenarios

The assignment's third scenario is **ambiguous requirements**. Anonymous/public access is the user's chosen API policy and is tested separately. Run the commands below from the repository root with Docker running. Saved-baseline upgrades need Git history containing the recorded P2/P3 source commits; a source-only archive can run current verification but cannot reproduce those historical builds.

| Scenario | What changes / what is proved | Run and expected result |
| --- | --- | --- |
| Greenfield | Foundation → new lifecycle service; strict inputs, code uniqueness, keyed transactions, GET/HEAD, disable and persistence | `python3 scripts/phase2-smoke.py`; PASS lifecycle/restart/outage checks; full verify runs core/race/rollback tests |
| Brownfield | Existing V1 data → additive nullable V2 expiry → current V3; old identities, destinations, disabling and key replay preserved | `python3 scripts/phase3-smoke.py`; PASS saved-baseline migration and expiry/restart checks; full verify tests exact temporal boundaries |
| Ambiguous analytics | Define what counts, UTC windows, retention, delay/loss; add V3 without destroying V2 state | `python3 scripts/phase4-smoke.py`; PASS eventual GET-only stats and persisted-event restart; `AnalyticsTest`/`AnalyticsIT` cover saturation, UTC, cleanup, shutdown and crash loss |
| Anonymous/public APIs | All routes work without a bearer token or login; global key namespace and unconfigured browser CORS | `bash scripts/api-curl-walkthrough.sh` against the running app; PASS public calls; full verify includes public exposure/unmapped-route/CORS assertions |
| Distributed state | Create on one replica, resolve/stats on another; Redis TTL non-renewal, outage fallback, passive failover and changed-IP replacement | `python3 scripts/phase5-smoke.py`; PASS isolated full topology; transient gateway responses during failover are recorded |
| Final handoff | Clean committed-source build, current stack, retained down/up, checksum failure blocks startup, recovery without deleting rows | `python3 scripts/phase7-smoke.py --output target/phase7-my-replay`; PASS and `report.json` with `passed: true`, `cleanup: true` |

Each scenario record contains intent, decomposition, execution, actual AI dispositions, validation, checkpoint and limitations: [greenfield](docs/scenarios/greenfield.md), [brownfield](docs/scenarios/brownfield.md), [ambiguous](docs/scenarios/ambiguous.md). Upgrade scripts seed historical schema through the saved migration job and SQL, then run current public-access code; they do not require an old API token.

The scripts create generated credentials and unique disposable projects. Only their own fixture volumes are removed. They build images and can take several minutes; they are not load tests. The curl walkthrough instead writes a few sample mappings into the already running stack and retains those rows after disabling/expiry. Do not run every historical smoke script against a production database or change them to point at development storage.

## Assignment requirements and review questions

This table addresses all eight core requirements and the deliverables in [problemStatement.md](docs/problemStatement.md). The assignment text is requirements context; implementation actions follow the user's requests. Authentication, rate limiting, unique visitors and production HA are not added completion criteria.

| Requirement / reviewer question | Answer and inspectable evidence |
| --- | --- |
| 1. How was intent understood and ambiguity normalized? | A runnable URL shortener with core APIs, analytics and reliability; [Phase 0 scope/defaults](docs/phase0-contracts.md), architecture assumptions and [analytics scenario](docs/scenarios/ambiguous.md) distinguish required behavior, user choices and unconfirmed product semantics. |
| 2. How were tasks sequenced and dependencies managed? | [Implementation plan](docs/implementation-plan.md): contracts → foundation → core → additive expiry → analytics → cache/replicas → measured reliability → handoff. Each scenario records its own decomposition and acceptance checks. |
| 3. What brownfield modules/data flows were impacted? | [Brownfield impact map](docs/scenarios/brownfield.md) covers parser/DTO → policy/service → JDBC/fingerprint → redirect → metadata → migration/readiness → contracts/tests. Saved before/after source revisions and V1/V2 regression evidence allow diff review. |
| 4. How was AI used beyond generating code? | [AI execution log](docs/ai-execution-log.md) records implementation, reproduced debugging, refactoring, test generation, documentation and review preparation. Tasks specify intent/context/constraints/acceptance; actual generated/edited/rejected choices have rationale. Secrets are excluded; tool outputs and assumptions are reviewed. No model identity or human rejection is invented. |
| 5. What engineering outputs were produced? | Java source, [OpenAPI](openapi.yaml), [schema definitions](docs/schema-plan.md), immutable Flyway SQL, unit/real integration tests, Docker/NGINX configuration, [architecture](docs/architecture.md), [decisions](docs/decisions.md), setup and handoff. Controller/service/policy/repository/configuration boundaries keep behavior maintainable. |
| 6. How are risks and failures controlled? | [Edge-case catalog](docs/edge-case.md), explicit transaction/retry/input/resource bounds, real PostgreSQL/Redis failures, no caller-runs analytics, cache freshness, graceful drain, fail-closed advisory gate, [actual validation](docs/validation-results.md) and [failed load targets](docs/performance/phase6-final/results.md). |
| 7. Who owns approval and correctness? | The engineer owns correctness, maintainability and readiness. User choices/access-policy authorization are recorded; assistant review is separate. [Final review record](docs/handoff/review.md) leaves human acceptance and unconfirmed defaults pending. No production deployment or destructive developer-data action is implied. |
| 8. Where is the final engineering summary? | [Engineering summary](docs/engineering-summary.md) explains plan/rationale, artifacts, decisions, results, assumptions, risks, trade-offs and limitations. |
| How do the required deliverables run end to end? | Follow local startup and annotated curls; clean-source handoff repeats build/start/create/redirect/stats/disable/restart and migration-failure handling. Three scenario records and tests provide the remaining change-history evidence. |

Evaluation criteria are supported by the same evidence: iterative AI dispositions demonstrate effectiveness; architecture/ADRs demonstrate design and defensible decisions; dependency sequencing/impact maps demonstrate decomposition and codebase reasoning; executed real-container tests demonstrate realistic output and risk rigor. Modular code, blocking gates and additive migrations support testability and safe change management. The honest loss/latency/failover limitations demonstrate engineering judgment. Passing local checks does not establish human acceptance or production readiness.

### Key design questions

- **Why PostgreSQL instead of H2 or Cassandra?** The selected problem needs atomic link/key creation, uniqueness, lifecycle persistence and UTC date aggregation. PostgreSQL satisfies these with one SQL/transaction model and vendor-real tests. H2 memory loses state on exit and differs in locking/migrations. Cassandra can support keyed lookup, but would require a different integrity/idempotency and aggregation design; no demonstrated scale requires that extra complexity. The user selected PostgreSQL; see ADR011.
- **How does it scale locally?** NGINX distributes requests to two stateless Spring Boot replicas; Redis reduces shared mapping SQL. Workers still write observations to the shared primary. Pools, workers and fallback must be budgeted across all replicas. Cache reduction is measured; maximum throughput, linear scaling and multi-host HA are unproven.
- **Why async analytics and what does it guarantee?** It avoids waiting for event SQL in the redirect path. The bounded in-memory queue is best effort and can lose data; committed retained events survive restarts. UTC stats describe observations, not exact people or completed browser visits. Durable transport is a future contract/operations decision.
- **What does keyed replay mean?** Repeating the same URL/expiry and global `Idempotency-Key` within 24 hours returns the original 201 creation result; a changed input conflicts. This protects a client retry after a lost response. Keys are optional and do not authenticate callers.
- **What authentication is configured?** None: no OAuth, account, bearer token or ownership enforcement. Database passwords authorize storage roles only. Any caller who can reach the app can use every public API; Compose binds ingress to localhost.
- **Why no rate limits?** They are outside the user-approved required prototype scope. Input, queue, connection and fallback bounds control internal resource use; these do not create a client quota or 429 policy.
- **Why can a disabled link still redirect briefly?** Cached mode permits stale mappings until the original read deadline, capped at 60 seconds and expiry. Invalidation is best effort; hits never extend that deadline. Use `REDIRECT_CONSISTENCY_MODE=strict` for primary-observed disabling. See the cache race tests and ADR020.

## Troubleshooting

| Symptom | Diagnosis / action |
| --- | --- |
| Java version / class-version error | Select Java 21 with `JAVA_HOME`; check `./mvnw -version`. Java inside the Docker image and on the host are separate. |
| Docker unavailable or integration tests fail before starting | Start Docker Engine/Desktop; check `docker version` and `docker compose version`. Mandatory integration tests must run, not be skipped. |
| First build cannot download artifacts/images | Restore registry/Maven network access and rerun; inspect build logs. Cached dependencies do not make first setup offline. |
| Port 8080 already in use | Stop the other topology/service or intentionally change the Compose published port and `PUBLIC_BASE_URL`. Use the same configured hostname to prevent self-target URLs. Isolated smoke checks select their own ingress port. |
| Missing configuration / login failure to PostgreSQL | Replace all three `.env` password placeholders, preserve fixed runtime/migration role names, and use the existing volume's original credentials. `.env` changes do not rotate initialized role passwords. Never delete the volume as a credential fix. |
| Migration failed / apps never start | `docker compose ps -a` and `docker compose logs --tail=100 migrate`; inspect history/source compatibility with storage preserved. Failure output is redacted. Do not edit applied SQL or auto-repair checksums. |
| Readiness DOWN but liveness UP | Database/schema unavailable or incompatible; inspect `docker compose logs --tail=100 postgres app-1 app-2` and migration completion. Health intentionally omits internals. |
| Stats still zero after a GET | Poll within a bounded interval; verify an active GET, not HEAD/error/expired redirect. Best-effort loss is possible. Investigate worker/database pressure; `asOf` does not prove ingestion is complete. |
| DELETE succeeds but a redirect remains | Allow the bounded cached disable window or select strict mode and recreate apps. HTTP responses alone cannot prove Redis TTL/hits; use cache integration/P5 smoke evidence. |
| Short failover gateway error | NGINX uses passive detection; short errors are possible when an app disappears. Check both app health/DNS recovery. Single-host composition is not HA. |
| Source-only/shallow download lacks old baseline | Current build/replay can run from tracked source; P3/P4 historical upgrade scripts additionally need the recorded Git revisions. Use a checkout containing that history. |
| Load runner exits with saved evidence | Inspect `validation.json` and `results.md`; runner completion is not proof every target passed. Compare elapsed validity, skipped arrivals, HTTP errors and event reconciliation. |

Keep logs and generated config private: do not share `.env`, full Compose-expanded config or credentials. API walkthroughs intentionally print request URL/headers/body, so use sample destinations when saving a shareable transcript.

## Recorded outcomes and remaining work

The full Phase 6 suite passed 99 unit + 54 real integration tests (153), with zero failures/errors/skips. The final indexed Maven scan covers 122 artifacts with zero findings after 19 initial package/advisory pairs were fixed. [Phase 7 replay](docs/handoff/phase7-replay/report.json) records a separate clean-source build and retained-volume lifecycle verification with source/image identity and cleanup.

Three healthy 100-requests/second comparisons passed their gates; warm mappings reduced SQL by about 98.6%. Hot/cold/outage cases missed scheduled arrivals, and the blocked analytics writer lost/failed 5,931 observations out of 6,001 successful redirects. [Raw/derived performance evidence](docs/performance/README.md) retains all failures and interrupted iterations.

Hosted CI, native-container/build-plugin audits, production sizing, TLS hosting, durable analytics, backup/restore and multi-host failover remain unverified or future work. Final human review is pending in [the review record](docs/handoff/review.md). This repository provides an implemented prototype and reviewable evidence, without treating those remaining items as completed.
