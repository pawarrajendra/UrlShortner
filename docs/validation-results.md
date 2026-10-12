# Validation Results

Record observed results, not expected targets presented as successes. Current scope includes Phase 0 artifact checks and Phase 1 runtime foundation verification. Core URL APIs are verified in Phase 2 below; expiry is verified in Phase 3 and bounded analytics in Phase 4. Redis/NGINX are verified in Phase 5; measured local load/failure evidence is recorded in P6 below.

Current API access was revised at the user’s request; the public-API verification below supersedes older access-policy assumptions. Dated phase results retain their original source/count context.

## Result template

| Field | Value to record |
| --- | --- |
| ID / task / edge cases | Stable reference |
| Source revision | Actual commit or explicitly uncommitted files |
| Environment | Runtime/version/configuration, isolated data, redacted secrets |
| Command / steps | Exact reproducible command and setup |
| Expected result | Defined contract or measured target |
| Observed result | Actual output and relevant evidence |
| Status | not-run / pending-decision / pass / fail / deferred-with-rationale |
| Limitations / review | What this check does not prove; human review state |

## Phase 0 checks

Executed 2026-10-10 against uncommitted workspace artifacts, using macOS Ruby 2.6.10, shell Python 3, and the bundled Python runtime with Pillow. Human acceptance remains pending. A limited structural/example validator is not a complete third-party OpenAPI conformance validator and cannot establish runtime behavior.

| ID | Command / action | Observed result | Status / limitation |
| --- | --- | --- | --- |
| P0-VAL-01 | `ruby -c scripts/validate-contract.rb` | `Syntax OK` | pass; checker syntax only |
| P0-VAL-02 | `ruby scripts/validate-contract.rb` | 8 operations, 7 DTO schemas; local refs, duplicate YAML keys, security, examples, headers, bodyless HEAD/204, phase markers, stats example totals/ranges and no required 429 checked | pass; structural/example subset, not full OpenAPI/runtime conformance |
| P0-VAL-03 | Inline Python walk of `docs/**/*.md`: count balanced fences, resolve local Markdown targets, require P0.1–P0.5 and check removed mandatory limiter references | 13 Markdown files, 62 local links passed at time of check | pass; reference/fence/scope checks, not rendered Markdown or runtime correctness |
| P0-VAL-04 | `/Users/raj/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 docs/diagrams/render_diagrams.py` | Both PNG assets regenerated | pass; bundled Pillow required, shell Python lacks it |
| P0-VAL-05 | View regenerated component and redirect-sequence PNGs | Legible nodes/arrows; Redis mapping cache and async worker flow shown without mandatory rate limiter | pass; assistant visual inspection, not human approval |

The static contract-check script uses only Ruby standard libraries. The OpenAPI document follows [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3.html), selected for compatibility; this is not a claim that it is the latest specification. Full third-party specification validation remains outstanding; the Phase 1 build does not add it.

## Phase 1 checks

Executed 2026-10-11 against uncommitted workspace files. Host build used Azul Java 21.0.10 and Maven Wrapper 3.9.16; Docker Desktop 4.94.0 / Engine 29.8.2 ran PostgreSQL `18.0-alpine`. Docker image runtime reported Java 21.0.12.1. Fixture credentials were generated/isolated; no existing development database was used. Human acceptance remains pending.

Host verification command (workspace dependency cache):

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache ./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply verify
```

| ID | Command / action | Observed result | Status / limitation |
| --- | --- | --- | --- |
| P1-VAL-01 | Wrapper `spotless:apply verify` above | BUILD SUCCESS; 0 Checkstyle violations; 6 unit + 7 integration tests; 0 failures/errors/skips; executable JAR packaged | pass; reports in `target/surefire-reports` and `target/failsafe-reports`; not hosted CI or clean-checkout replay |
| P1-VAL-02 | `FoundationIT` using disposable PostgreSQL Testcontainers | Fresh V1 and non-destructive replay; uniqueness/Base62/FK constraints; UTC microsecond round trips; runtime cannot perform DDL, delete links or modify migration history | pass; no API concurrency or later schemas tested |
| P1-VAL-03 | `FoundationIT` random-port app and packaged migration processes | Health minimal UP; other routes denied; missing/incompatible schema ready DOWN/live UP; migration non-web success/replay exits 0; invalid checksum exits nonzero with safe failure message | pass; no URL routes yet |
| P1-VAL-04 | `python3 scripts/phase1-smoke.py` | PASS: isolated Compose startup, migration, readiness/liveness, retained restart, DB outage/recovery and non-destructive migration replay | pass; fixture preserved after restart/replay; DB outage ready 503 DOWN/live 200 UP; own generated containers/volume removed afterward |
| P1-VAL-05 | `ruby scripts/validate-contract.rb`, `bash -n infra/postgres/init/10-roles.sh`, `python3 -m py_compile scripts/phase1-smoke.py` | All passed | pass; syntax/static checks do not prove later API behavior |

Debugging evidence: the first Docker-backed integration run failed because a one-connection migration pool could not supply Flyway's additional connection. The migration profile was changed to two connections. Pre-migration standalone validation was removed so pending V1 can apply; `migrate()` validates existing history, and explicit validation follows migration. The expanded seven-test integration suite and Compose smoke then passed. Runtime pool remains eight connections.

Docker image packaging deliberately skips tests; its build success alone is not a quality gate. The host `verify` result above is the test evidence. GitHub Actions is configured to run clean verification with Docker, but has not been executed on GitHub. Base-image digest pinning, dependency/security audit, full OpenAPI conformance and final engineer approval remain outstanding.

## Phase 2 checks

Executed 2026-10-11 against the Phase 2 workspace, before saving baseline commit `b098f8f87cbd3d64580b4db174bda24af609b1d6`. Same host Java/Maven/Docker/PostgreSQL versions as Phase 1. Each Testcontainers database and Compose project was isolated from development data; neither smoke script reads `.env`.

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache ./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
python3 scripts/phase2-smoke.py
ruby scripts/validate-contract.rb
ruby scripts/validate-contract.rb --phase2
```

| ID | Observed evidence | Status / limitation |
| --- | --- | --- |
| P2-VAL-01 | Clean BUILD SUCCESS; Spotless and Checkstyle passed; 54 unit tests + 22 PostgreSQL integration tests; zero failures/errors/skips | pass; 22 includes seven foundation and 15 core integration cases; hosted CI not run |
| P2-VAL-02 | Destination tests cover allowed protocols, ASCII preservation, Unicode path serialization, public/private IPs, punycode, self-host rejection, whitespace/control/credential/malformed input, numeric aliases, size limits and unpaired surrogates | pass; no DNS/reputation guarantee |
| P2-VAL-03 | Full HTTP create → inspect → GET/HEAD → disable, repeated disable, current metadata and original keyed replay; microsecond UTC output; no internal UUID/expiry field; unknown/trailing/extra routes; 405/Allow; public redirects | pass; no external redirect destination was followed |
| P2-VAL-04 | Barrier-forced concurrent code collision retries once in a fresh transaction; repeated disabled-code collision exhausts at exactly five attempts; no overwrite/new rows on exhaustion | pass; collision/exhaustion counters implemented; no distributed replicas/load claim |
| P2-VAL-05 | Barrier-forced matching key race returns one result; conflicting key race returns one 201/one 409; losing candidate rolls back. Four simultaneous expired-key requests produce one new link. Replay before and replacement exactly at 24-hour boundary verified | pass; fixed injected clock exercises boundary; unknown network commit outcomes remain intentionally unclassified |
| P2-VAL-06 | Key INSERT permission failure rolls back the candidate; invalid generated code check failure is not retried as a collision; original retained links remain | pass; real PostgreSQL constraints/roles used |
| P2-VAL-07 | Cross-origin preflight and strict JSON unknown/duplicate/types/trailing tokens, invalid UTF-8, wrong media type, repeated/invalid keys, exact/oversize declared and chunked body checks | pass; safe envelope/request ID/no-store checked; HEAD errors bodyless |
| P2-VAL-08 | `phase2-smoke.py` builds and starts fresh Compose, creates/inspects/GET/HEADs a link, preserves active link/key across app/DB restart, returns 503 dependency error during DB outage, recovers, replays migrations, disables twice, replays original keyed result after disable and preserves disabled/key state across another restart | pass; own generated containers/network/volume removed after completion; a volume is not a backup |
| P2-VAL-09 | Static validation of planned final OpenAPI and implemented P2 checkpoint: 8/7 and 7/5 operations/schemas respectively; smoke script syntax checked | pass; structural/example subset, not full third-party specification conformance |

Debugging/review: an initial test compilation failed because the 24-hour test omitted a checked exception declaration; it was corrected. The first PostgreSQL core run then exposed that `SELECT ... FOR UPDATE` requires UPDATE privilege on key records, while V1 intentionally grants SELECT/INSERT/DELETE. The implementation now reads the key and conditionally DELETEs stale state using digest, original timestamp and cutoff. DELETE locks/rechecks the row; zero deleted rows trigger a fresh-transaction retry. This avoids rewriting applied V1 or broadening runtime privileges. Concurrent replacement and rollback tests passed after the change.

Baseline source checkpoint and actual greenfield decomposition/execution/review are recorded in [greenfield](scenarios/greenfield.md). Human approval is pending and is separate from assistant verification.

## Phase 3 checks

Executed 2026-10-11 against Phase 3 source saved as `20b9159ef8a35accfb42c2165141d2f1b49acbe2`, from source baseline `b098f8f87cbd3d64580b4db174bda24af609b1d6`. Java/Maven/Docker/PostgreSQL versions are unchanged from earlier checks. Source diff and migration boundaries are recorded in [brownfield](scenarios/brownfield.md). Human acceptance remains pending.

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache ./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
python3 scripts/phase3-smoke.py
ruby scripts/validate-contract.rb --phase3
```

| ID | Observed evidence | Status / limitation |
| --- | --- | --- |
| P3-VAL-01 | Clean BUILD SUCCESS; formatting and zero Checkstyle violations; 76 unit + 28 PostgreSQL integration tests; zero failures/errors/skips | pass; 28 includes 20 core and eight foundation integration tests; prior collision/key/rollback regressions retained |
| P3-VAL-02 | Apply V1 only, seed fixed active/disabled UUIDs, URLs/timestamps and P2 golden key digests/fingerprint, then apply V2; old rows remain null-expiry and old key replays original metadata | pass; production V1 byte content unchanged; no destructive backfill |
| P3-VAL-03 | V1-only current app live UP/ready DOWN, packaged runner applies V2 then ready UP; database rejects expiry equal to creation; runtime role restrictions and migration failure/replay checks retained | pass; no interrupted-DDL recovery or downgrade claim |
| P3-VAL-04 | GET/HEAD just one nanosecond before expiry return 302; at expiry return 410, HEAD bodyless/no Location. Metadata remains accessible; expired mapping retained; disabling takes error-code precedence | pass; injected Clock determines the exact boundary |
| P3-VAL-05 | Past/equal new expiry, wrong types, malformed/missing offsets, leap seconds, excess precision and UTC range crossings rejected. Equivalent offsets share UTC microsecond output/fingerprint. Matching replay after expiry/disable succeeds; changed expiry returns 409 before future check | pass; canonical null encoding remains P2-compatible |
| P3-VAL-06 | Generator advances Clock during creation; captured microsecond creation time still yields valid 201, followed immediately by 410 for the very short link | pass; response delivery does not promise remaining lifetime |
| P3-VAL-07 | Packaged saved-P2/V1 → current-P3/V2 retained-volume upgrade and restart/outage/expiry checks | pass; saved P2/V1 app upgraded on the same fixture volume; old IDs/URLs/times/disabled state/keys/V1 checksum preserved, expiry and restart/outage/replay verified; own fixture containers/network/volume removed; final normalized-instant/bounded-polling script rerun also passed |
| P3-VAL-08 | Root planned-final, archived P2 and current P3 structural/example validation passed; script syntax and local Markdown links checked | pass; not full third-party OpenAPI validation |

Review/debugging: assistant source review corrected an accidental extra JDBC key-insert argument during expiry propagation before integration execution. The first P3 contract check then caught an example-expansion helper mistakenly treating schema property maps as response examples. The helper now recognizes actual string-valued examples; all three checkpoint validators passed. No runtime test failure or human rejection is invented. All observed runtime tests passed on the first full Phase 3 verification run.

## Later-phase verification

- Expiry V1→V2 and core URL/concurrency/rollback checks passed above. Analytics V3 is implemented and verified in P4 below.
- Redis cache, absolute deadlines and invalidation/refill races: verified in P5 below.
- NGINX/two-replica routing: verified in P5 below. Bounded analytics and shutdown/loss behavior are verified in P4 above.
- Load target and persisted analytics reconciliation: P6 local observations below; no production-throughput claim.
- Clean-checkout setup replay and human final acceptance: pending.

## P4-001 — Bounded asynchronous analytics and ambiguity resolution

Date: 2026-10-11. Baseline: P3 source `20b9159ef8a35accfb42c2165141d2f1b49acbe2`, latest evidence `bd4cebb`. Source changes are captured in the P4 checkpoint recorded below. Human semantics/diff acceptance is pending.

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home \
MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache \
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
# Final test-only lifecycle regression also rerun with spotless:apply verify.
python3 scripts/phase4-smoke.py
ruby scripts/validate-contract.rb
ruby scripts/validate-contract.rb --phase2
ruby scripts/validate-contract.rb --phase3
ruby scripts/validate-contract.rb --phase4
```

| Gate / cases | Observed result |
| --- | --- |
| Compilation, formatting and Checkstyle | Passed, zero style violations |
| Unit tests | 92 passed (76 existing + 16 analytics), zero failures/errors/skips |
| PostgreSQL integration tests | 40 passed (28 existing + 12 analytics), zero failures/errors/skips |
| ASY-01/08/10/13 | Paused recorder: GET returns 302, immediate stats zero, HEAD/invalid methods/missing/disabled/expired/failed lookup excluded; resume and bounded polling observes persistence |
| ASY-02/04/05/16 | Configurable one-worker/one-slot saturation deterministically rejects additional work without caller-runs; exactly 5 seconds accepted, +1 ns dropped; closed admission and forced drain metered |
| ASY-06/07 | PostgreSQL FK insert failure leaves valid redirects successful; controlled INSERT failure rolls back; controlled commit exception classified `commit_unknown`, without retry |
| ASY-11/12 | Worker paused across UTC midnight; link disabled after valid observation; event still committed to its original day |
| ASY-14/15 | Fresh application context reads committed history; actual in-flight HTTP request completes and publishes while close waits, event commits before Hikari closes |
| Hard-stop pending loss | Separate child JVM accepts one executing and one queued GET with recorder paused; parent kills child forcibly; zero event rows, mapping retained and still resolves. No final-drop metric expected |
| STA range/retention | UTC half-open bounds, asOf cap, exact 30-day cutoff, future zero buckets, same-query total, default seven days, duplicate/unknown/partial/malformed/overlong date errors all passed |
| Bounded cleanup | At most two test rows per table per run, exact cutoffs retained, event history excluded before physical cleanup; clock +999 ns regression keeps query/cleanup microsecond cutoffs aligned |
| Concurrent cleanup/key replacement | Cleanup waits behind an uncommitted replacement; after commit, current identity/time predicates preserve the newly recreated key |
| V3 storage/least privilege | Event UUID deduplication and link FK enforced; runtime event UPDATE/DDL denied; SELECT/INSERT/DELETE available |
| Migration safety | V1 and V2 bytes match saved P3 exactly; V3 is additive; migration runner/guard/readiness updated together to 3 |
| Structural API checks | Root and P2/P3/P4 snapshots passed; root/P4 identical. Limited structural/example validator, not full external conformance validation |
| Documentation/scripts | Python syntax, local Markdown links/fences and `git diff --check` passed |
| Packaged Docker upgrade/persistence | Passed on the final runtime rerun: isolated saved-P3/V2 → P4/V3, existing state/checksum retained, GET-only stats, committed history through app/PostgreSQL restart, expiry and DB outage/recovery; fixture volume/network removed |

The clean build passed after the precision fix; the final test-only disable-during-queue assertion also passed in full verification (132 tests). Earlier passes had 130 tests before adding actual HTTP shutdown and delayed-day regressions. No observed runtime failure is invented. Assistant review found a possible nanosecond/JDBC rounding mismatch and aligned cleanup with stats' microsecond snapshot before adding the boundary regression.

V1 SHA256: `3d04a7ff7bd1a1f891ebf5a1ef3205a7f448a3bd7883b0f0de3f8a547e6600ef`; V2 SHA256: `dce78ef5a27e4d1880c79618f40735d32e32c1da1d792c247e4fb84e11fa2dbd`. Both compared byte-for-byte with the saved P3 revision.

Limits: deterministic queue tests reduce capacity to force the boundary; default 2/1,000 sustained throughput is unmeasured. Unknown commit classification is injected, not a real network-partition result. The shutdown cancellation test observes pending drops/interruption, not guaranteed cancellation of arbitrary remote I/O. Metrics are registered locally with bounded labels; no public metrics exporter is enabled. Cross-replica/cache/load/audit, clean-checkout replay, backup/restore and human final acceptance remain later work. Generated containers/volumes are isolated fixtures; no development storage is removed.

**Final packaged rerun:** `python3 scripts/phase4-smoke.py` passed after the cleanup precision correction. Disposable project `urlshortener-p4-0312125253` was removed with its own volume/network. V3 replay was non-destructive; saved P3/V2 metadata/key state survived upgrade. The final full Maven run finished successfully with 92 unit + 40 integration tests; hard-stop loss demonstration passed again. No project `.env` or development volume was used.

**P4 source checkpoint:** `23f50223065b0b2b3da03174a7688e55fb30ff25` (`23f5022`, Implement Phase 4 bounded asynchronous analytics). The evidence-only follow-up records this identifier without changing the verified runtime. No remote push.


## P5-001 — Shared Redis cache and two-replica deployment

Date: 2026-10-11. Baseline: P4 source `23f50223065b0b2b3da03174a7688e55fb30ff25`, evidence `8004a07`. Local P5 source checkpoint is recorded after these checks. Human acceptance remains pending.

Environment: Java 21, Spring Boot 4.1.0, Maven 3.9.16, Lettuce 7.5.2.RELEASE, PostgreSQL 18.0-alpine, Redis 8.2.1-alpine, NGINX 1.28.0-alpine, Docker Desktop Engine 29.8.2. Tests use real PostgreSQL/Redis and disposable credentials/storage.

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home \
MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache \
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
python3 scripts/phase5-smoke.py
ruby scripts/validate-contract.rb
ruby scripts/validate-contract.rb --phase5
docker run --rm -v /Users/raj/Documents/ChatGPT/UrlShortner/infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro nginx:1.28.0-alpine nginx -t
```

| Gate / behavior | Observed result |
| --- | --- |
| Build/style and unit tests | Passed; 99 unit tests, zero failures/errors/skips |
| PostgreSQL/Redis integration tests | Passed; 48 tests, including 8 new CacheIT cases; zero failures/errors/skips; 147 total |
| Shared resolution and stats | app-1 creates, app-2 resolves; committed stats visible from both; warm hits leave the mapping SELECT counter unchanged |
| Original absolute freshness | SET PXAT equals original primary statement time + 60 seconds capped by expiry, floored to milliseconds; hits do not renew; delayed fill preserves original deadline |
| Lifecycle and corruption | Strict mode reads disabled primary despite warm mapping; disable commits before DEL; ACL-denied DEL keeps disable committed; stale entry converges; invalid JSON/version/code/deadline, eviction and real Redis restart fall back/refill |
| Cache precision | One captured cache-hit observation prevents a clock-boundary race; freshly read primary can resolve before exact expiry even after the conservative millisecond Redis deadline |
| Bounded dependencies | Real Redis pause triggers bounded fallback/breaker; recovery warms cache; controlled same-key SQL pause coalesces; fallback saturation and follower timeout return controlled 503 and release permits |
| Full Docker topology | One app artifact, two instances, shared PG/Redis, gated migration/startup, NGINX least connections; twenty warm HEADs reach both instance IDs with no-store and unchanged deadlines; cleanup only on app-1 |
| Replica stops | app-2 stop: stable app-1 probes after 1.055 s, no transient errors observed. app-1 stop: stable app-2 probes after 1.606 s, one transient 504 without app instance header |
| Redis stop/recovery | Eight ingress HEADs return 302 during outage; primary readiness stays UP; restarted cache refills |
| Container replacement | Old app-2 IP held by isolated fixture; replacement obtains different IP. NGINX reaches new app-2 with app-1 stopped and no NGINX reload |
| Ingress lifecycle | Short expiry yields 302 then 410; disable returns 204 then 410 after invalidation |
| Schema compatibility | V1/V2/V3 unchanged from P4; schema version remains 3, no P5 migration or runtime DDL grant |
| Contract/docs | Root and all archived phase contracts pass limited structural/example checks; root/P5 identical; syntax, local links/fences and whitespace checks pass |
| Final proxy configuration | nginx -t passed after adding response request/instance identifiers to access logs; routing configuration unchanged from full topology check |

Final disposable project `urlshortener-p5-f6a84bc781` passed and its containers, network, volume and old-IP holder were removed. No development volume or project .env was used. Stop timings start after blocking Compose stop completes and include five consecutive survivor probes; they are local observations, not a failure-to-recovery SLA. NGINX uses passive failure detection and can return a gateway error during transition.

Debugging dispositions: initial integration fixtures manipulated a Spring repository proxy rather than its target and changed Redis's published port on restart. Corrected the target access and reserved a stable private host port. Cold Redis connections can legitimately fall back on their first request; fault/deadline tests now establish a warm entry with bounded polling. Smoke iterations corrected nullable Docker IPAM metadata, warmup assumptions, stability polling after a transient 504, and JSON parsing of HTML gateway errors. Earlier failures were not counted as passing checks. Temporary exception diagnostics were removed before final clean verification.

Limits: cached-disable convergence tests advance the application clock while keeping the original DB timestamp/Redis deadline; they do not measure 60 wall-clock seconds or physical clock skew. Redis connect and command each have a 100 ms bound and may occur consecutively; this is not an end-to-end HTTP latency guarantee. Default capacity, ingestion lag, sustained throughput and production clock discipline remain unmeasured (P6). Redis/NGINX/database/single host remain failure points, analytics queues remain disposable, hosted CI/clean-checkout replay/final human acceptance remain pending. Legacy P4 packaged upgrade evidence is retained; its Docker script was not rerun in P5.

**P5 source checkpoint:** `f9f9a424abe277a03789a85d593757abd759cf2b` (`f9f9a42`, Implement Phase 5 shared Redis cache and two-replica NGINX topology). This evidence-only follow-up records the verified source identifier; no runtime changes or remote push.


## CURL-001 — Phase-wise sample API walkthrough

Date: 2026-10-11. Added [sample-curl.sh](../scripts/sample-curl.sh), grouped into Phases 0–5 with separate P6/P7 verification references. Script-only changes use the verified P5 image `urlshortener-p5-f6a84bc781:test`; no Java/schema change or repeated full Maven claim.

Checks executed: `bash -n scripts/sample-curl.sh`, local Markdown links and `git diff --check`; then `bash scripts/sample-curl.sh` with BASE_URL pointing to an ephemeral localhost NGINX port. Disposable Compose project `urlshortener-curl-0e0d756311` started from the current topology and was removed with only its own volume/network afterward. No .env or development stack was used.

Observed: walkthrough passed. Live/ready/ingress 200; invalid destination/past expiry/incomplete stats range 400; creation and matching replay 201 with identical code; changed keyed input 409; public GET/HEAD 302 with correct Location/no-store; unsupported method 405; invalid code 404; short expiry converged to 410; matching expired replay retained the code. Three observed GET attempts became visible in best-effort UTC stats, whose daily sum equals the selected total. Warm HEAD observations included app-1 and app-2. Disable/repeated disable returned 204, resolution converged to 410, metadata retained disabledAt, original replay still returned disabledAt null without reactivating the mapping. Sample analytics link was disabled.

Limits: this script is a functional walkthrough, not a load test, migration replay, outage harness, internal Redis/SQL measurement or human acceptance. Least-connections need not show both replicas in every short run, and async losses/lag can prevent its bounded stats expectation under degraded conditions. On a user's stack, sample mappings/creation keys remain by design; unique keys prevent reuse of previous sample runs. Single-app baseline mode skips ingress-only health. No test of that optional baseline mode was run in this check.


## CURL-002 — Explicit curl URLs with API purpose and response validation

Date: 2026-10-11. [api-curl-walkthrough.sh](../scripts/api-curl-walkthrough.sh) places each curl call directly in the shell script, with comments explaining purpose, expected response and validation. Shared helpers inspect status/headers/JSON only; they do not hide HTTP commands.

Executed: `bash -n scripts/api-curl-walkthrough.sh`, Markdown links/fences and whitespace checks; full `bash scripts/api-curl-walkthrough.sh` run against verified P5 image `urlshortener-p5-f6a84bc781:test` in disposable project `urlshortener-curl-65154f7e55`, generated credentials and ephemeral localhost ingress port. All checks passed: health UP, valid code/destination/lifecycle/short URL, 201 same-code replay, 409 conflict, metadata, GET/HEAD 302/Location/no-store/request ID, 405/Allow, 404/LINK_NOT_FOUND, expiry 410/LINK_EXPIRED, three eventual observations and bucket sum/mode, explicit UTC dates, 400/INVALID_DATE_RANGE, warm replica headers, repeated disable 204/empty body, 410/LINK_INACTIVE convergence and retained disabled metadata. Both app-1 and app-2 appeared among the HEAD observations.

Test containers/network/volume were removed; existing development data was not used. This is script-level HTTP evidence, not renewed Maven, internal Redis/SQL measurement, load/outage/migration validation or human acceptance. Two sample mappings remain on a user's stack by design; this isolated fixture's mappings were disposed with its own volume.



## CURL-004 — Request purpose and outgoing request logs

Date: 2026-10-11. Both walkthroughs now print a UTC send timestamp, check purpose, API behavior, method, resolved URL/query, request headers, body and request timeout immediately before each transfer, including polling. [curl-request-log.py](../scripts/curl-request-log.py) parses the actual curl argument list. The explicit walkthrough retains every curl call and uses a small logging wrapper; its transcript goes to stderr so status capture remains correct. User-Agent is explicitly set and logged. Existing status/header/body assertions follow the call.

Observed: logger argument checks passed for POST body, content length, key, timeout and HEAD/no-body. Bash/Python syntax, Markdown and whitespace checks passed. Both complete walkthroughs passed with isolated fixture settings on the verified P5 image in disposable project `urlshortener-curl-f8ffe58ac6`; own containers/network/volume removed. The captured combined transcript contains 102 timestamped request blocks, each with purpose/API behavior/method/URL/headers/body/timeout. JSON expiry and keyed headers appear. No development requests or new Maven/throughput/acceptance claim.


## ACCESS-001 — Public APIs across the project

Date: 2026-10-11. User explicitly requested removal of the application credential gate from the entire project. Source: current uncommitted workspace changes on P5 checkpoint `f9f9a424abe277a03789a85d593757abd759cf2b` (including the previously requested curl walkthroughs). Human authorization is this scope request; final assignment/product acceptance remains pending.

Removed the application access filter, Spring Security dependencies/configuration, startup token setting and local/example Compose setting, private curl credential helper/configuration and credential-related examples/statuses. All creation/metadata/disable/statistics routes now accept requests without credential headers. Runtime/migration PostgreSQL roles/passwords, URL/body validation, safe errors, lifecycle/cache semantics, global idempotency encoding and V1–V3 SQL remain intact. Updated current and phase-feature API definitions, architecture/decisions/contracts/plan/scenarios/edge catalog/setup and scripts. Original phase contracts/code remain at their recorded Git revisions; feature snapshots in this checkout are reconciled with the new public access scope.

Executed commands:

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home \
MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache \
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
python3 scripts/phase5-smoke.py
python3 scripts/phase4-smoke.py
python3 scripts/phase3-smoke.py
ruby scripts/validate-contract.rb
ruby scripts/validate-contract.rb --phase2
ruby scripts/validate-contract.rb --phase3
ruby scripts/validate-contract.rb --phase4
ruby scripts/validate-contract.rb --phase5
docker compose build app-1
```

| Gate | Observed result |
| --- | --- |
| Clean Java/style gates | Passed; 99 unit + 48 PostgreSQL/Redis integration tests, zero failures/errors/skips (147 total) |
| Public API regression | Creation, metadata, stats and disable work without credential headers; no challenge header; invalid JSON remains 400 with no write |
| Request/CORS behavior | Unknown routes are 404; preflight is 200 with no allowed-origin header; browser cross-origin access is not configured |
| Dependencies/config | Packaged JAR contains no Spring Security libraries or removed access filter; local .env and examples contain no API-token setting |
| Full topology | Disposable `urlshortener-p5-02455de40e`: cross-app create/resolve/stats, warm deadlines, app stops, Redis stop/recovery, expiry/disable and changed-IP replacement all passed; own test containers/network/volume/holder removed |
| Passive failover observation | app-2 stop: 1.045 s; app-1 stop: 1.392 s until stable survivor probes; no transient failures observed in this run. Timings begin after Compose stop completes; historical P5 run's transient 504 remains disclosed |
| Saved V2 upgrade | `urlshortener-p4-c172579fdd`: saved P3 migration-only job → seeded legacy rows/keys → current V3 app; identities/key encoding/checksum/disabled state preserved, stats commit/restart, expiry and DB outage checks passed; own fixture removed |
| Saved V1 upgrade | `urlshortener-p3-32d5893237`: saved P2 migration-only job → seeded V1 rows/keys → current V3 app; identity/key/checksum/lifecycle/restart/outage/expiry checks passed; own fixture removed |
| Both curl walkthroughs | Passed against rebuilt image in disposable `urlshortener-curl-b25479d9a8`, with no exported API credential and no private auth file; own fixture removed |
| Sample transcript | `curl-walkthrough.log` regenerated from the public-API explicit walkthrough; contains purpose/time/URL/headers/body/validation and no credential/challenge/401 lines |
| Contracts/static/diagram | Root and P2–P5 structural/example checks passed; root/P5 identical; Python/Bash syntax, links/fences, whitespace and unchanged migration bytes verified; regenerated component diagram inspected |
| Local image | Default `urlshortener:local` rebuilt successfully for the next local Compose startup; no development services/volumes stopped or deleted |

Actual debugging: the first post-removal run exposed two security-era expectations (restricted built-in health route and preflight 403). An attempted web-exposure exclusion also removed the HealthEndpoint bean required by the existing aliases, causing startup failures; restored the health bean/exposure with details suppressed and validated unknown-route 404 and unconfigured preflight 200 instead. Final clean verification passed. A temporary curl-validation harness indentation error occurred before any fixture started; corrected before the successful functional run. These failed attempts are not counted as successful gates.

Limits: revised upgrade smoke tests execute saved migrations and seed historical-schema rows directly; they do not run the old HTTP application. Previously recorded old-app upgrade evidence remains historical. All reachable callers can create/read/disable links and read counts; no account/ownership system is introduced. Local proxy/cache/database/single-host and best-effort analytics limitations remain. This is not new load, hosted CI, backup/restore, clean-checkout or final human-acceptance evidence.


## P6-001 — Reliability, patched dependency gates and measured local load

Date: 2026-10-11 (local). Baseline: P5 source `f9f9a424abe277a03789a85d593757abd759cf2b`, plus previously authorized public-API/curl workspace changes. P6 source checkpoint is recorded after final checks. Final human acceptance remains pending; no hosted deployment or remote push.

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home \
MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache \
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache spotless:apply clean verify
./mvnw -B -ntp -Dmaven.repo.local=.m2-cache org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree \
  -DoutputType=json -DoutputFile=target/dependency-tree.json
python3 scripts/dependency-audit.py --output docs/performance/dependency-audit.json
python3 scripts/phase6-load.py --keep-awake --output docs/performance/phase6-final
python3 scripts/phase6-report.py docs/performance/phase6-final
```

| Gate | Observed result |
| --- | --- |
| Build/style/units | Passed; 99 unit tests, zero failures/errors/skips |
| Mandatory real integration | Passed; 54 PostgreSQL/Redis tests, including 6 ReliabilityIT cases; zero failures/errors/skips; 153 total |
| P6.1 pool/lock/query | Hold all eight connections → bounded 503/recovery; actual held row makes disable roll back safely within lock budget; pg_sleep cancellation leaves connection usable |
| P6.1 database network | Real PG pause: already-fresh Redis HEAD remains 302; primary/strict work fails boundedly, readiness 503, liveness 200; unpause restores primary resolution |
| P6.2 events/shutdown | Existing queue saturation/age and write/unknown-commit tests retained; actual in-flight HTTP-first graceful drain and hard-killed child loss run in the full suite. Slow-writer load reports actual loss rather than declaring fast responses healthy |
| P6.3 review | Source review covers database secrets/roles, URL/JSON policy, SQL bindings, safe errors/logs, forwarding, metrics isolation and public-API scope; no new account/token model |
| P6.4 owner/restart | Close cleanup owner, seed expired rows while observer excludes them, restart owner with two-row batches and observe backlog catch-up; existing real database/app persistence tests remain mandatory |
| P6.5 generator/environment | Pinned k6 1.3.0, 10,000 deterministic links, constant 100 arrivals/s for 60s, no redirects followed; exact Docker/CPU/memory/heap/pool/executor bounds and dataset/image/source hashes retained |
| P6.5 three comparisons | One/no-cache: 6,000 attempts, 5,983 successful/persisted; two/no-cache: 6,000 successful/persisted; two/warm-cache: 6,001 successful/persisted. All three healthy targets pass, with zero measured analytics loss/failure and full successful-event reconciliation |
| Mapping SQL reduction | Two/no-cache: 6,000 SELECTs; two/warm-cache: 86 (~98.6% reduction), with original deadlines preserved and refreshes near expiry included |
| Latency | Final all-status per-replica server p95 maxima 1.76 / 1.69 / 2.61 ms; corresponding client/proxy p95 3.93 / 3.45 / 4.72 ms. These are local observations, not maximum capacity or production sizing |
| Variant failures | Hot-key/cold-cache/Redis-outage have 11/7/7 skipped arrivals and remain failed against the strict scheduling gate. All successful redirects in those variants reconcile to persisted events. No scheduling failure is silently waived |
| Slow analytics | 6,001 successful redirects; 70 commits/rows, 5,423 queue-age losses and 508 known write failures. Healthy target failed. Queue peak 528, oldest sampled age 5.428s, no unknown commits observed |
| Dependencies | Initial 122-package scan had 19 package/advisory pairs. Coordinated minor-line patches applied; final indexed scan passes with zero findings. Actual before/after affected ranges and resolved versions retained; no finding suppressed |
| Measurement endpoint | Explicit measure profile; endpoint uses unpublished port 9090, absent from main/default API. All-status GET timer includes success/error and excludes HEAD; snapshot test rejects datasource/password leakage |
| Local image | Verified default `urlshortener:local` rebuilt with P6 patches/support; no development service/volume changed |
| Static/CI | Python/Bash syntax, public API snapshot structural checks, workflow YAML, local links/fences and whitespace reviewed; CI adds fail-closed advisory/contract gates and retained reports. Hosted CI was not run |
| Cleanup | Final project `urlshortener-p6-14a4d1ace8`, initial `urlshortener-p6-5728313ce1`, intermediate `urlshortener-p6-4915296c1c`, and setup fixtures removed only their own containers/network/volume. Development state not used or removed |

The authoritative table/raw artifacts are [final load evidence](performance/phase6-final/results.md), [gate evaluation](performance/phase6-final/validation.json), [environment/counters](performance/phase6-final/report.json) and [source/dependency review](performance/source-review.md). Each case retains k6 output, before/after meters and sampled resources. V1–V3 SQL and the public API contract are unchanged by P6.

Actual iteration history: required-brace style and a fixture ID/import compiler error were corrected before tests passed. The first advisory query failed because the local Python CA bundle was missing; curl system trust was used with certificate verification intact. A nested dataset bind mount under a read-only script mount failed before load; dataset mount moved to /data. A k6 option warning was corrected to maxRedirects. Short setup check passed 501 redirects/commits with no errors/loss, but is not a 60-second target result.

Two initial 60-second variant configurations reported 459/618 seconds of k6 elapsed time, so those comparisons are invalid; host/VM pause is an inference, not a diagnosed cause. Their raw output is retained. An intermediate awake rerun exposed a worst-status-group percentile interpretation; the final image adds one all-status GET timer per replica and repeats the entire matrix. The report applies a documented 0.1-second lower rounding tolerance to elapsed-window validity and retains original runner flags; no raw counts, timings, errors, skipped arrivals or failed thresholds are altered. Normalized gate evaluation is in validation.json. A sampled-resource end-of-generator race was fixed; final cases have 14 samples and no sampling errors each.

Limits/disposition: this measures a fixed 100-rps offered workload under recorded resource bounds, not maximum sustainable or linear throughput. Strict skipped-arrival failures remain failures; they do not by themselves identify a server bottleneck. Slow-writer event loss is the existing bounded best-effort contract, not durable delivery. This is assistant source review and known-indexed Maven audit; native containers/build plugins, actual network lost-commit-ack, physical clock-skew tolerance, backups/restoration, hosted CI, clean-checkout handoff and final human acceptance remain unverified. Phase 7 remains outstanding.

**P6 source checkpoint:** `a3c359a5b4f5348fa92b080c6df90f44eebd0016` (`a3c359a`, Implement Phase 6 reliability, measured load and patched dependency gates). The local checkpoint also preserves prior authorized public-API/curl changes. Evidence follow-up records the identifier and scopes whitespace checking away from raw CLI transcripts; source formatting checks remain enabled. No remote push.

## P7-001 — Clean-source handoff and requirement-complete README

Date: 2026-10-11 America/Chicago (raw UTC report ends 2026-10-12). Environment: macOS 15.6.1 ARM, Azul Java 21.0.10, Maven Wrapper 3.9.16, Docker Desktop Engine 29.8.2, PostgreSQL 18.0-alpine, Redis 8.2.1-alpine and NGINX 1.28.0-alpine. Source exported by `git archive`: `fc82a265285e694a9334d19c2c8bd0532c2153ad`. Source excludes `.env`, target/build output and untracked files; host prerequisites and the ignored Maven download cache are reused. No application, dependency or migration change is part of P7.

Executed command (from repository root):

```bash
JAVA_HOME=/Users/raj/Library/Java/JavaVirtualMachines/azul-21.0.10/Contents/Home \
MAVEN_USER_HOME=/Users/raj/Documents/ChatGPT/UrlShortner/.m2-cache \
python3 scripts/phase7-smoke.py --output docs/handoff/phase7-replay
```

The runner executes `./mvnw -B -ntp -Dmaven.repo.local=<repository>/.m2-cache clean verify` from the exported tree, then builds and starts the default full Compose topology using a unique project, private generated credentials and random localhost ingress port. [Final report](handoff/phase7-replay/report.json) identifies the source, exact image, project, checks and cleanup; [verify log](handoff/phase7-replay/verify.log), [image build log](handoff/phase7-replay/build.log) and [runner transcript](handoff/phase7-replay/replay.log) retain results.

| Check | Observed result |
| --- | --- |
| Clean-source full build/style | BUILD SUCCESS in 1:13; 99 unit + 54 real PostgreSQL/Redis integration tests, zero failures/errors/skips |
| Fresh startup | V1/V2/V3 history and public readiness UP; two replicas behind ingress |
| Lifecycle | Create and original keyed replay 201; metadata; exact 302/Location/no-store; bodyless HEAD; one GET event eventually persisted |
| Disable | 204, redirect converges to 410, missing HEAD 404 without body |
| Retained-volume shutdown/startup | `down` then `up` preserved disabled mapping, original keyed creation response and exactly one committed event |
| Migration failure handling | Only fixture V1 history checksum changed; migration exit 1 with redacted `Startup failed`; Compose startup failed and neither app was running |
| Non-destructive fixture recovery | Restored original fixture checksum, replayed migrations, preserved disabled mapping/event; no data reset |
| Cleanup | `cleanup: true`; only this unique test project's containers/network/volume removed; development services/data untouched |

The [initial attempt](handoff/phase7-initial/report.json) is retained and marked failed. It passed build/lifecycle/restart but the fixture expected the literal word `checksum` in failure output. The existing application correctly redacts startup details. The assertion was corrected to expect `Startup failed` plus nonzero exit; the whole replay was repeated, not relabeled successful. Both attempts cleaned their fixtures. Restoring a deliberately corrupted fixture checksum is test teardown, not advice for repairing real migration history.

Documentation verification: all five structural contract checks (`ruby scripts/validate-contract.rb`, plus `--phase2`, `--phase3`, `--phase4`, `--phase5`) passed; root/P5 OpenAPI agree. Python/Bash syntax, changed-document local links/anchors/fences, migration identity and Git whitespace checks passed. Existing PNG component/sequence diagrams were visually inspected; both are readable and match the implemented topology/freshness/async flow. The README now covers setup/runtime configuration, public APIs, annotated requests, all scenario tests, failure/load gates, troubleshooting and all eight core assignment requirements/evaluation questions.

Limits: this clean tracked-source export verifies the installed host, not a fresh machine, uncached/offline install or another OS. Saved-baseline upgrade history is verified separately by P3/P4 records. Advisory evidence remains the dated P6 scan; no new dependency change or claim of a new audit is made. All measured scheduling/analytics failures remain disclosed. Hosted CI, native-container audit, backup/restore, production HA/capacity and final human sign-off remain unverified. [Human review record](handoff/review.md) explicitly remains pending.
