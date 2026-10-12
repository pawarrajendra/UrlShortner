# AI Execution Log

Maintain actual evidence throughout implementation. Never manufacture generated/edited/rejected examples, executed tests, commit references, or human approvals. Redact secrets and submitted destination data. Links to evidence must identify actual artifacts or results.

## Entry template

- Entry/task ID and phase/scenario:
- Date, tool/model (record model only if known):
- Requirement/intent and source context:
- Prompt summary, constraints and acceptance criteria:
- Relevant files and source revision:
- Disposition: generated / edited / rejected:
- Rationale and iteration/debug/refactor notes:
- Validation commands and actual results:
- Risks/assumptions remaining:
- Assistant review and separate human review/sign-off status:

## P0-001 — Scope, API/schema contracts, and evidence templates

- Date: 2026-10-10. Tool: Codex assistant with workspace file tools; runtime model identifier not independently recorded.
- Request: implement Phase 0 of the implementation plan, without implementing later phases.
- Context: architecture, problem statement, phase plan and edge-case catalog. Constraint: rate limiting remains optional; no fabricated approvals/runtime results.
- Generated: `openapi.yaml`, Phase 0 contract/schema documents, scenario/evidence templates and a static contract-check script.
- Edited: architecture/decision consistency for optional rate limiting and V1/V2/V3 staging; Phase 0 status and edge-case gap cross-reference.
- Rejected during drafting: treating earlier shared-limiter/429 and Redis-dependent management proposals as mandatory. Rationale: user questioned their scope and the plan explicitly excludes them. Also rejected automatic OpenAPI schema validation as proof of runtime correctness.
- Validation: actual commands/outcomes are recorded in [validation-results.md](validation-results.md), not assumed here.
- Risk: implementable defaults are still product assumptions; full server behavior, SQL migrations, performance and dependency compatibility require later-phase validation.
- Review: assistant static consistency review performed; human product/contract acceptance pending. No public deployment, production mutation or destructive change.

## P1-001 — Runnable application and persistent foundation

- Date: 2026-10-11. Tool: Codex assistant with file/shell tools, Maven, Docker Compose and PostgreSQL Testcontainers; runtime model identifier not independently recorded.
- Request: implement Phase 1; user subsequently confirmed Docker installation and asked to continue verification.
- Sources/constraints: implementation plan, architecture, Phase 0 contracts/schema; Java 21, Spring Boot, PostgreSQL; no H2, no required rate limiter, no Phase 2 URL endpoints.
- Generated: POM/Maven Wrapper, application/config/security/health/migration classes, V1 SQL, bootstrap roles, unit/integration tests, Dockerfile/Compose, CI/style configuration, `.env.example`, smoke script and README.
- Edited: migration pool changed from one to two connections after a real integration failure; adjusted migration validation order to permit pending V1 while retaining checksum validation. Added executable-process failure/replay and schema readiness tests. Updated phase status, architecture, schema plan, engineering summary and validation evidence.
- Rejected implementation alternatives: H2 replacement and Docker-unavailable test skipping would weaken the required real PostgreSQL gate; automatic runtime DDL credentials would violate role separation. These were design alternatives reviewed by the assistant, not invented human rejection events.
- Debugging: initial integration run failed on Flyway connection acquisition. Two migration connections resolved it; runtime remains bounded to eight. See [validation results](validation-results.md) for actual commands and outcomes.
- Validation: six unit tests and seven mandatory PostgreSQL integration tests passed with zero failures/errors/skips, formatting/style passed, executable JAR packaged. Isolated Compose startup, restart persistence, DB outage/recovery and migration replay passed. Disposable project data was removed afterward; existing development data was not used.
- Remaining limitations: URL routes, expiry, async analytics, Redis, NGINX and replicas are later phases. CI is authored, not run on GitHub; no load, audit, restore or clean-checkout claim.
- Review: assistant implementation/test/documentation review performed. Human acceptance and final assignment review are pending; no fabricated approval or commit reference.

## P2-001 — Greenfield core APIs and transactional creation

- Date: 2026-10-11. Tool: Codex assistant, workspace/shell tools, Maven, PostgreSQL Testcontainers and Docker Compose; model identifier not independently recorded.
- Request: implement Phase 2 of the plan. Constraints: retain V1, omit expiry/analytics/cache/rate limits, satisfy greenfield execution/validation and save a source baseline for Phase 3.
- Generated: link domain/response types, SecureRandom code generator, destination/idempotency policies, JDBC repository, transactional service, strict bounded JSON controller, safe error handler, unit/core integration tests, P2 OpenAPI checkpoint and smoke entry point.
- Edited: public redirect routing and strict request validation. Shared isolated smoke helper gained real HTTP lifecycle/restart/outage checks. README, architecture, schema/phase/scenario/validation/summary/decision records now distinguish implemented P2 from future work.
- Rejected implementation output: the first `SELECT ... FOR UPDATE` key lookup was replaced after actual PostgreSQL tests failed under the established least-privilege role. Granting UPDATE or rewriting applied V1 would unnecessarily expand permissions or undermine brownfield migration evidence. Conditional stale DELETE with current timestamp/cutoff predicates provides locking/recheck without either change.
- Debugging: fixed a checked-exception declaration in a test; diagnosed the runtime key-lock permission failure; retained separate bounded code/key retry counters and fresh transactions. Added concurrent stale-key replacement and explicit CORS regression coverage.
- Validation: clean build/style gates; 54 unit + 22 PostgreSQL integration tests passed with zero failures/errors/skips. Forced code/key races, atomic rollback, 24-hour boundaries, strict HTTP parsing and bodyless HEAD verified. Packaged Docker lifecycle/restart/key/disable/outage check passed; generated test state was disposed. Both OpenAPI checkpoints passed the limited structural/example validator.
- Review preparation: [greenfield record](scenarios/greenfield.md), current [P2 contract](openapi-phase2.yaml), [validation evidence](validation-results.md) and setup/examples in README; local baseline commit `b098f8f87cbd3d64580b4db174bda24af609b1d6` saved after verification. No hosted deployment or remote push.
- Remaining risks: DNS can change; no reputation check; unkeyed retries can duplicate; unknown commit outcome requires keyed recovery; no key cleanup scheduler yet. V2/V3, async/cache/topology/load, audit and backup/restore remain later work.
- Review status: assistant implementation/diff/contract/test review performed; human engineer acceptance remains pending. Implementation-plan checkpoint authorization does not imply product sign-off.

## P3-001 — Brownfield additive expiration

- Date: 2026-10-11. Tool: Codex assistant with file/shell tools, Maven, PostgreSQL Testcontainers and Docker Compose; model identifier not independently recorded.
- Request: implement Phase 3. Starting point: tested P2 source `b098f8f87cbd3d64580b4db174bda24af609b1d6`; impact map in [brownfield](scenarios/brownfield.md).
- Generated: V2 nullable expiry/check SQL, strict expiration policy and tests, P3 API checkpoint, saved-baseline Docker upgrade smoke entry point.
- Edited: domain/response/SQL projections and INSERT now carry immutable nullable expiry; create service preserves legacy fingerprints and checks replay before future time; redirect checks disabled then expired. Migration target, startup guard and readiness updated together to 2. Core/foundation tests extend the actual V1 baseline with golden keys/rows; existing race/rollback tests remain. Setup/status/decisions/contract/scenario/evidence docs updated.
- Rejected approaches during assistant design/review: rewriting applied V1, silently rounding seven-plus-digit timestamps, assigning expiry to old rows, or adding Redis before brownfield validation. Rationale: preserve real compatibility and isolate the additive change. These are assistant-reviewed alternatives, not fabricated human rejection events.
- Debugging/refactoring evidence: review removed an accidentally duplicated expiry JDBC argument from key insertion before integration execution; structural contract check caught overly broad example traversal, which was narrowed to actual response-example strings.
- Validation: clean Maven verification passed 76 unit + 28 PostgreSQL integration tests with zero failures/errors/skips. V1-seeded upgrade and legacy-key replay, exact lifecycle boundaries, UTC offset/precision/range, safe invalid expiry, disabled precedence, short lifetime and existing concurrency/rollback regressions passed. All three staged API structural validators passed. Packaged saved-P2/V1 → P3/V2 upgrade, retained restart/outage/replay and expiry checks passed. The final smoke helper compares normalized instants and polls expiry with a bounded deadline to avoid representation/sleep assumptions; its rerun is recorded in [validation results](validation-results.md).
- Source checkpoint: `20b9159ef8a35accfb42c2165141d2f1b49acbe2` after verification; before/after IDs are recorded in the brownfield scenario. No remote push.
- Migration review: V1 unchanged; V2 adds one nullable column and one CHECK, no reset, update/backfill or new privilege. Old null-expiry fingerprints match P2 exactly. Database role scopes are retained.
- Risks: past expiry blocks new creation but not matching replay; very short links may expire before delivery. P2 ignores expiry and is not a safe serving downgrade once expiring links exist. No cache, analytics, scheduled key cleanup, backup/restore, interrupted migration recovery or production rolling-upgrade claim.
- Review: assistant implementation/migration/diff/test/documentation review performed. Human migration/product sign-off pending; authorized local test work is not stakeholder acceptance. No public deployment or remote push.

## P4-001 — Ambiguous analytics implemented with explicit delivery limits

- Date: 2026-10-11. Tool: Codex assistant, file/shell tools, Java 21 Maven, PostgreSQL Testcontainers and Docker Compose. Exact model identifier not independently recorded.
- Request: implement Phase 4 from the plan. Starting point: P3 source `20b9159ef8a35accfb42c2165141d2f1b49acbe2`; semantics and decomposition are in [ambiguous scenario](scenarios/ambiguous.md).
- Generated: V3 events/indexes/grants, analytics configuration/immutable event and ticker, bounded publisher, separate recorder transaction/failure outcome, UTC range parser/stats DTO/service/controller, bounded retention scheduler, explicit shutdown lifecycle, 16 unit and 12 PostgreSQL integration tests, test-only child JVM for crash loss, P4 API snapshot and isolated Docker smoke entry point.
- Edited: resolution now returns UUID/destination/observation timestamp after SQL; controller submits only active GETs. Migration target, configuration guard and readiness advance together to 3. Core tests stage V1→V2→V3; incompatible-history fixture now uses version 4. Compose exports bounded analytics settings and allows teardown margin. Root contract, README, architecture, decisions, schema/plan/scenario/edge/evidence/summary records distinguish implemented P4 from future P5–P7.
- Reviewed alternatives rejected: caller-runs/unbounded admission, synchronous hot counters, unique-visitor claims, unbounded retries/cleanup, extra UPDATE grants for key row locking, and incidental destruction-order assumptions. These are design alternatives evaluated by the assistant, not fabricated rejected human patches. Durable transport remains a possible future requirement.
- Refactoring/implementation choice: explicit named `ThreadPoolExecutor` replaces the proposed task-executor/proxy adapter while preserving fixed workers, bounded queue and AbortPolicy. A separate `EventRecorder` provides transaction and test boundaries; monotonic enqueue ticks support exact age tests and metrics.
- Review correction: cleanup initially used untruncated wall time. JDBC rounding could disagree with stats' microsecond cutoff. It now uses the same truncation; a +999 ns retention regression verifies the exact event/key cutoff survives. No failed runtime result is claimed for this review-discovered issue.
- Tests/debugging: initial full suite passed 130 tests. Added real HTTP-first shutdown and delayed UTC day regressions; clean suite passed 132. Final full verification also passed after extending delayed recording to disable the link before commitment. Write and commit errors are exercised separately; no blind retry. Hard-stop child is test-only and uses no production pause endpoint.
- Validation: 92 unit + 40 PostgreSQL integration tests passed with no failures/errors/skips. Root/P2/P3/P4 limited structural contract checks, Python syntax, Markdown links/fences and whitespace review passed. Saved P3/V2 Docker upgrade, metadata/key/checksum preservation, retained restart, dependency outage/recovery, GET-only stats and committed history persistence passed; final rerun/checkpoint are in [validation results](validation-results.md).
- Migration review: V1/V2 match saved source bytes. V3 adds an event table, two indexes and SELECT/INSERT/DELETE grants; no event UPDATE, visitor identity or DDL permission expansion. Runtime and migration credentials remain separated.
- Risks/ownership: best-effort loss and eventual counts, shared-pool contention, physical-cleanup backlog, framework lifecycle phase coupling, no measured throughput/ingestion p95, no actual network lost-ack test, and pending human semantics/diff acceptance are disclosed. No public deployment or remote push.
- P4 source checkpoint: `23f50223065b0b2b3da03174a7688e55fb30ff25` after verification; local-only commit. Evidence follow-up changes documentation only.


## P5-001 — Shared cache and scalable local topology

- Date: 2026-10-11. Request: implement P5. Baseline: P4 source `23f50223065b0b2b3da03174a7688e55fb30ff25`. Tools: Codex file/shell assistance, Maven, Docker, real PostgreSQL/Redis Testcontainers. Exact model identifier not independently recorded.
- Generated: typed versioned cache codec, no-op/Redis adapters, validated settings, absolute deadline cache-aside resolver, bounded connection/command waits and breaker, coalescing/fallback permits, seven unit/eight integration regressions, NGINX configuration and isolated topology smoke script.
- Edited: repository captures primary statement timestamp; service delegates resolution and invalidates after committed disable; metadata supplies server instance IDs; default Compose runs two apps from one artifact and app-1 owns cleanup. Preserved cache-disabled baseline Compose, P4 schemas and analytics semantics. Updated setup/contracts/architecture/ADR020/evidence and diagram cache key.
- Reviewed alternatives rejected: sliding hit TTL, a new deadline after delayed fill, synchronous analytics, automatic unsafe POST replay, runtime schema grants, Redis-dependent readiness, and client rate limits. These are assistant design dispositions, not fabricated human patch rejections. Direct managed Lettuce avoids unnecessary Redis autoconfiguration in the migration job.
- Review corrections: conservative millisecond TTL must not reject an otherwise fresh primary observation before exact expiry; cache hits use one captured observation to avoid a second clock read crossing the boundary. Loser coalescing paths release fallback permits before waiting. Dedicated regressions cover these resource/lifecycle boundaries.
- Actual debugging: integration failures exposed proxy-target fixture misuse and restarted Redis port changes; corrected AopTestUtils target access and fixed private port binding. Subsequent cold-connection warmup failure led to bounded cache establishment. Smoke failures led to nullable IPAM handling, warmup/deadline stability polling, consecutive survivor observations and content-type-aware gateway error parsing. Removed temporary diagnostics. A skipped targeted invocation was replaced by enabled verification and was not counted as executed tests.
- Validation: final clean build passed 99 unit + 48 integration tests with zero failures/errors/skips. Full isolated Docker topology passed shared reads/stats, warm deadlines, both upstreams, cleanup ownership, each app stop, Redis outage/recovery, expiry/disable and replacement at a different IP without NGINX reload. One transient 504 observed during app-1 stop is disclosed in [validation results](validation-results.md). Final log-only proxy edit passed nginx -t. Contract/static/diagram review completed; source checkpoint recorded after verification.
- Limits/oversight: no throughput claim, physical clock-skew test or new human approval. Cached lifecycle staleness remains bounded under the documented clock assumptions; strict mode requires primary. Connection/command bounds are separate, fallback and queues are bounded, analytics remain best effort. P6/P7 and human acceptance are pending. No public deployment or remote push.

- P5 source checkpoint: `f9f9a424abe277a03789a85d593757abd759cf2b`, local only; evidence follow-up records its identifier without changing the verified implementation.


## CURL-001 — Sample curl walkthrough by phase

- Date: 2026-10-11. Request: generate sample curl commands for overall functionality, separated by implementation phase.
- Generated: Bash curl request helper with explicit statuses, generated-code capture, unique idempotency keys and bounded polling, P1 health, P2 core/replay/conflict, P3 expiry, P4 eventual UTC stats, P5 replica observations and disable convergence. P0 and P6/P7 explain scope and separate verification work.
- Edited: README run instructions and script limitations; repaired a split inline Compose upgrade command found during documentation inspection. Runtime/schema unchanged.
- Design disposition: keep service stops/load/internal cache proofs in the existing disposable smoke/integration tests; HTTP headers alone cannot prove Redis hits or SQL reduction. Avoid automatic POST retry, redirect following, hard deletion or executing .env as shell code. No human rejected output/acceptance claimed.
- Validation: Bash syntax, links and whitespace checks passed; entire walkthrough passed against an isolated running P5 image/two-app topology, observed both instances, and fixture teardown completed. Actual commands/results and remaining limits are recorded in [validation results](validation-results.md#curl-001--phase-wise-sample-api-walkthrough). No full Maven rerun was needed for this script/documentation change. No remote push or deployment.


## CURL-002 — Explicit annotated API curl commands

- Date: 2026-10-11. Request refinement: shell script with curl URLs, what each API does, and how to validate its response.
- Generated: [api-curl-walkthrough.sh](../scripts/api-curl-walkthrough.sh), direct curl commands grouped by phase; comments explain endpoint semantics and expected statuses/headers/JSON, followed by executable assertions. Response helper functions do not issue HTTP. UUID-based creation key and generated codes keep reruns independent; temporary request/response capture keeps commands reproducible.
- Edited: README points to the direct-command version. Kept the prior broader sample script available. No Java/schema change, service stops or remote publishing.
- Validation: Bash syntax/static documentation checks passed; full explicit walkthrough passed on the verified P5 image in disposable two-replica Compose, including both replica observations. Its test containers/network/volume were removed. See [CURL-002](validation-results.md#curl-002--explicit-curl-urls-with-api-purpose-and-response-validation) for actual results and limits. No human approval/rejected output or renewed full-suite result invented.



## CURL-004 — Log what each API call sends and does

- Date: 2026-10-11. User requested per-call purpose and outgoing URL/header/body logging.
- Generated: shared actual-curl-argument logger with UTC send time, explicit method/full URL/query, endpoint explanation, configured/default headers and formatted JSON or no body. Added per-call purpose to each explicit curl block and logger invocation inside the broader request helper, covering loops automatically. Added a reproducible diagnostic User-Agent.
- Edited: README describes the transcript and `2>&1 | tee curl-walkthrough.log`. Sending logs go to stderr before transfer so existing HTTP status capture/response validations still work.
- Validation: focused actual-argument/request-argument checks and syntax/docs checks passed. Both full fixture-backed walkthroughs passed in disposable two-replica Compose; transcript request fields and request fields verified. Fixture teardown completed. No Java/schema change, development calls, new full Maven result or human acceptance claimed.


## ACCESS-001 — Remove the application credential gate

- Date: 2026-10-11. Explicit user request: public APIs throughout the project and relevant files. This request authorizes the API access change; final assignment acceptance remains separate.
- Removed: runtime access filter, Spring Security dependencies/chain/default-user scaffolding, startup/environment/example token setting, private curl credential helper/config flags/headers and obsolete access-status examples. The local .env was edited only to remove that obsolete setting; database credentials were preserved.
- Edited: required web settings, safe errors, all integration callers and access regressions, baseline/full Compose, both curl walkthroughs and actual-argument logging, root and P2–P5 feature contracts/validator, README/architecture/ADR008/plan/edge/scenarios/evidence and client diagram. The sample transcript now shows the public-API walkthrough. Idempotency retains the existing global digest prefix and all applied SQL bytes remain unchanged.
- Design disposition: preserve persisted key replay instead of changing its encoding when removing caller identity. Saved-schema smoke runs use the historical migration job and SQL-seeded rows, then the current app; they do not retain an old HTTP credential dependency. Original source/contract history remains in Git; checkout feature snapshots describe the revised scope.
- Actual debugging: two initial test assumptions no longer held after removing the security chain. Web-exposure exclusion caused the required HealthEndpoint bean to disappear; restored minimal health configuration, and verified unmapped-route 404 plus unconfigured-CORS preflight 200. Fixed an indentation error in a temporary test harness before it could start a fixture.
- Validation: final clean build/style passed 147 tests (99 unit/48 integration), all without an API token setting/header. Full disposable topology, saved V1 and V2 migration/seed upgrades, both public curl walkthroughs and default local-image rebuild passed. Five structural contracts, syntax, local links/fences, whitespace, JAR dependency/class absence, migration identity and regenerated diagram checks passed. All isolated fixtures were removed; no development service/volume was deleted. Detailed observations are in [ACCESS-001](validation-results.md#access-001--public-apis-across-the-project).
- Limits: public callers share API access and idempotency namespace. Database role access controls remain necessary for storage. P6/P7, measured capacity and final human acceptance remain pending. No public deployment or remote push.


## P6-001 — Measured reliability and security gates

- Date: 2026-10-11 local. Request: implement P6; retain public APIs and no client rate limits. Tooling: Codex workspace/shell, Maven/Java 21, real PostgreSQL/Redis Testcontainers, isolated Docker Compose, pinned k6 and public OSV advisory data. Exact model identifier not independently recorded.
- Generated: six real reliability tests (pool/lock/query/network/cleanup recovery/private metrics), profile-only whitelisted measurement snapshot and all-status GET timer, deterministic 10,000-link fixture/k6 matrix/resource sampler, saved-evidence report generator, fail-closed dependency checker and performance/source-review records.
- Edited: BOM patch properties, CI dependency/contract/artifact gates, setup/architecture/ADR021–022/plan/edge/evidence/summary. Public routes, SQL migrations, code/key/lifecycle and best-effort event semantics are retained; existing authorized public-API/curl changes are preserved in the source checkpoint.
- Dependency review: actual initial query found 19 package/advisory pairs across 122 artifacts. Used exact primary affected/fixed ranges to patch related families together. Final query found zero; no unsupported unused-feature/false-positive waiver. Native container/build-plugin audit remains unclaimed.
- Actual debugging/refinement: fixed required braces and a fixture-only internal ID/import error; replaced the local Python CA failure with verified system curl trust; corrected a read-only nested volume mount and unsupported k6 option. Retained raw interrupted 459/618-second windows and repeated them awake. Replaced misleading worst-status-group percentile comparison with an explicit all-status GET timer and repeated the complete seven-case matrix. Corrected sub-second duration-bound rounding in derived validation, retaining initial runner flags and all raw values. Fixed sampler handling of a generator disappearing at completion.
- Rejected approaches: following destination redirects, measuring only HTTP speed while ignoring dropped events, silently renewing warm TTLs, merging status-group percentiles, attributing long interrupted windows to app capacity, hiding indexed findings or treating local routing as HA. These are assistant-reviewed choices, not fabricated human patch rejections.
- Verification: final clean build/style passed 99 unit + 54 real integration tests (153), zero failures/errors/skips. Three healthy topology comparisons passed and successful events reconcile after drain; warm Redis reduces mapping SQL by ~98.6%. The final hot/cold/outage scheduling failures and slow-writer 5,931 loss/failures are explicitly retained. Source review, indexed dependency gate, contracts/static checks and private/default measurement exposure regressions passed. Exact commands/environment and raw evidence are in [P6 validation](validation-results.md#p6-001--reliability-patched-dependency-gates-and-measured-local-load).
- Oversight/limits: assistant review performed; human final acceptance remains pending. No load threshold, credential scope or durable-delivery contract is silently changed to turn failures into passes. All fixtures removed only their own data. P7, native-image auditing, hosted CI/clean checkout, restoration, physical clock tolerance and production capacity remain outside this result. No remote deployment/push.

- P6 source checkpoint: `a3c359a5b4f5348fa92b080c6df90f44eebd0016`, local only. A final indexed whitespace review caught expected tool-banner/protocol trailing spaces in raw CLI captures after the checkpoint was saved. Preserve captured bytes and scope Git whitespace exclusions to those transcript paths; source checks remain strict. The evidence follow-up changes metadata/documentation only.

## P7-001 — Reproducible handoff and full README

- Date: 2026-10-11 local. Requests: implement P7; expand README from setup through running and greenfield/brownfield/ambiguous/public-access testing, addressing the problem statement. Context: completed P0–P6 source, preserved scenario/checkpoint history, public APIs and disclosed load failures. Constraints: do not reset development volumes, invent human approval, alter API/schema or hide failed targets. Acceptance: clean-source build/full topology replay, migration-failure blocking, retained restart, complete evidence/summary and requirement mapping.
- Generated: tracked-source archive replay harness with isolated credentials/project/image/port/volume, build and lifecycle assertions; submission handoff and reviewer checklist; finished engineering summary; requirement/question/configuration/scenario/troubleshooting sections in README.
- Edited: architecture's obsolete proposed configuration/package layout and cache/decision status, ADR implementation status/key/field names, phase completion record and validation traceability. Corrected stale README claim that key cleanup was not implemented. Inspected existing PNG diagrams visually. Preserved assignment scope, current API/OpenAPI, SQL bytes and all P6 failure evidence.
- Actual debugging: first replay passed build/lifecycle/restart, then failed a fixture assertion requiring detailed checksum text. The application's startup error is intentionally redacted. Corrected the assertion to require nonzero exit and `Startup failed`, retained initial failed report/logs, and repeated the full build/stack replay. Then verified actual Compose startup blocking and recovery on restored fixture history.
- Dispositions: generated drafts were edited to replace template instructions and future-tense implementation claims with actual evidence. Rejected the incorrect test expectation of verbose startup errors; retained redaction. Rejected treating anonymous access as a substitute for the assignment's ambiguous-requirement scenario; documented both. These are assistant choices, not fabricated human rejections.
- Verification: clean-source Maven/style passed 99 unit + 54 real integration tests. Full Compose create/replay/GET/HEAD/stats/disable/retained down-up, failed migration/startup-block and fixture recovery passed; only owned fixtures removed. Five contracts, syntax, documentation links/anchors/fences, migration identity and whitespace reviewed; exact commands/results in [P7 validation](validation-results.md#p7-001--clean-source-handoff-and-requirement-complete-readme).
- Ownership: user choices and explicit public-access authorization remain recorded. Assistant review complete; engineer final review/acceptance remains pending in [review record](handoff/review.md). No remote push/deployment, destructive developer-data operation, new runtime feature or acceptance of unconfirmed product assumptions is claimed.
