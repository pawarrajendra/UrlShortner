# URL Shortener: Phase-wise Implementation Plan

## 1. Purpose, sources, and current status

This plan converts [architecture.md](architecture.md) and [problemStatement.md](problemStatement.md) into sequenced implementation tasks, deliverables, and validation gates. [decisions.md](decisions.md) records the design rationale, including bounded asynchronous analytics in ADR-015.

Phase 0 contracts and evidence templates are authored. Phases 1–6 are implemented and locally verified on 2026-10-11: Java/Spring Boot foundation, V1 migrations, health checks, separate database roles, Docker Compose, tests and CI configuration, plus core URL APIs/idempotency additive expiry, UTC stats, bounded asynchronous events and retention, plus Redis caching and NGINX/two-app deployment. See [validation results](validation-results.md) and [setup](../README.md). P6 adds real failure/wait tests, dependency patches and measured local load evidence. Phase 7 technical handoff and clean-source replay are complete; final human acceptance remains pending in [the review record](handoff/review.md).

The assignment's 2–3 day timebox calls for a demonstrable prototype with maintainable code and engineering evidence. The deployment is a local scalability demonstration, not a production high-availability system.

### Scope precedence

The assignment requires core APIs, analytics, reliability, three engineering scenarios, AI traceability, tests, and handoff documentation. The user selected Java/Spring Boot, PostgreSQL, NGINX, Redis, Docker, and asynchronous analytics as the implementation direction.

Rate limiting was an assistant-added architectural choice, not an explicit user requirement. It is **optional and excluded from the required implementation phases**, including shared Redis request counters, client quotas, limiter-specific `429` behavior, and Redis-dependent management admission. Phase 0 reconciles the architecture and decision records to this scope; quota controls are not completion gates. Redis is required here for mapping caching. Bounded worker queues, database connection budgets, input size limits, and controlled database fallback remain resource protections, not client rate limits.

Other proposed architecture choices—idempotency, cache freshness, and analytics retention—remain design assumptions to record and review. Do not describe them as requirements stated by the assignment.

Current public-API revision: user-requested access simplification is implemented and verified; no application credential setup is required. See [ACCESS-001 evidence](validation-results.md#access-001--public-apis-across-the-project).

## 2. Delivery sequence and dependencies

```text
P0 Contracts and scope
  -> P1 Runnable foundation and PostgreSQL
  -> P2 Greenfield core API checkpoint (no expiry or Redis yet)
  -> P3 Brownfield expiration enhancement
  -> P4 Ambiguous analytics contract and async implementation
  -> P5 Redis, NGINX, two replicas, Docker topology
  -> P6 Reliability, security review, performance evidence
  -> P7 Setup replay and submission handoff
```

Keep a reviewed source-control checkpoint after P2 and before P3. A brownfield scenario must modify a working baseline, rather than present the final schema as if an upgrade had been performed.

| Phase | Suggested allocation | Main deliverable | Depends on |
| --- | --- | --- | --- |
| P0 | Day 1, first hour | Reviewed scope and contracts | Source documents |
| P1 | Day 1, 2–3 hours | App boot, database, migrations, test harness | P0 |
| P2 | Day 1, 3–4 hours | Working greenfield APIs and checkpoint | P1 |
| P3 | Day 2, 1–2 hours | Backward-compatible expiration upgrade | P2 |
| P4 | Day 2, 3–4 hours | Async analytics, stats, retention, scenario evidence | P3 |
| P5 | Day 2–3, 3–4 hours | Local scalable topology and cache behavior | P4 |
| P6 | Day 3, 2–3 hours | Failure tests, quality gates, measured load comparison | P5 |
| P7 | Day 3, 1–2 hours | Reproducible setup and reviewed submission | P6 |

These are scheduling estimates, not throughput promises. If implementation exceeds the timebox, prioritize core behavior and required evidence, defer optional work, and report incomplete gates. Do not claim completion based on elapsed time. Contract drafting and documentation can accompany coding, but dependent phases must retain their checkpoints.

## 3. Phase 0 — Confirm scope and define contracts

**Goal:** Turn the assignment and architecture into a clear implementation boundary before generating code.

| Task | Work | Output / acceptance |
| --- | --- | --- |
| P0.1 | Separate assignment requirements, user choices, and proposed assumptions | Scope checklist including optional rate limiting |
| P0.2 | Specify creation, metadata, disable, stats, GET/HEAD redirect, and health routes | Initial `openapi.yaml`, DTOs, safe error codes, status/header examples |
| P0.3 | Define the V1 baseline and additive V2 expiration change | Schema plan with named constraints and explicit migration boundaries |
| P0.4 | Record observed-GET semantics, eventual stats, retention, and cache disable freshness | Reviewed assumptions; `asOf` is not an ingestion watermark |
| P0.5 | Create engineering evidence templates | AI log, scenario records, validation results, final-summary outline |

The final API surface follows `/api/v1/links` and `/r/:code`. P2 initially omits expiry and analytics fields that are introduced later; update the contract at each checkpoint. Preserve documented version compatibility or explicitly record additive changes.

**Exit gate:** Each required behavior has an acceptance condition and a planned artifact. No optional feature is silently promoted to a requirement. Record engineer review and unresolved assumptions without inventing stakeholder approval.

## 4. Phase 1 — Bootstrap the application and persistent foundation

**Goal:** Establish a reproducible Java application with real PostgreSQL integration tests.

| Task | Work | Output / acceptance |
| --- | --- | --- |
| P1.1 | Pin Java 21 and a compatible Spring Boot patch; add Maven Wrapper | Repeatable build and application entry point |
| P1.2 | Configure MVC, validation, Security, JDBC, Flyway, Actuator, and test dependencies | Minimal dependency set; no JPA or H2 substitution |
| P1.3 | Add validated configuration, injected `Clock`, logging, and redaction | Invalid required settings fail startup without revealing secrets |
| P1.4 | Create V1 `links` and `idempotency_records` schema without `expires_at` | UUID IDs, unique Base62 code, foreign keys and UTC timestamps |
| P1.5 | Implement the non-web migration runner and schema readiness check | Migration failure exits nonzero; incompatible schema fails readiness |
| P1.6 | Add a local PostgreSQL service, runtime/migration roles, and persistent volume | Separate credentials; repeat startup preserves committed state |
| P1.7 | Configure Checkstyle/Spotless, JUnit, PostgreSQL Testcontainers, and initial CI | `./mvnw verify` blocks on applicable failures; required container tests cannot silently skip |

Docker Desktop and Compose are available and the isolated Phase 1 smoke check passed on 2026-10-11. Development and Testcontainers databases must be isolated. Apply migrations from scratch, rerun them without destructive replay, and test UTC round trips. Never apply tests to a user's persistent development database.

**Exit gate:** Clean build, migrated database, healthy app, meaningful migration integration test, and clear failure behavior for missing database/schema. Record commands and actual outcomes.

**Observed status:** P1.1–P1.7 implemented. Formatting/style gates, six unit tests and seven PostgreSQL integration tests passed. Isolated Compose verified migration, health, retained restart, database outage/recovery and migration replay. CI is configured but has not run on GitHub; human acceptance and clean-checkout replay are pending. See [validation results](validation-results.md).

## 5. Phase 2 — Build the greenfield core service

**Goal:** Deliver create, resolve, inspect, and disable behavior against PostgreSQL before adding caching.

| Task | Work | Output / acceptance |
| --- | --- | --- |
| P2.1 | Implement destination URL policy and request validation | Absolute HTTP/HTTPS; bounded body/URL size; invalid inputs produce safe errors |
| P2.2 | Add eight-character `SecureRandom` Base62 generator and JDBC repositories | Database uniqueness enforces correctness under concurrent creation |
| P2.3 | Implement creation with optional `Idempotency-Key` | Link/key transaction is atomic; matching replay returns original result; mismatched fingerprint gives `409` |
| P2.4 | Implement bounded collision retries in fresh transactions | Save-time uniqueness races are handled; exhausted retries return controlled `503` |
| P2.5 | Implement metadata and irreversible disable operations | Existing repeated disable returns `204`; mappings/codes are retained |
| P2.6 | Implement explicit GET and HEAD redirect handlers | `302`, correct `Location`, `Cache-Control: no-store`; missing `404`, disabled `410`; unsupported methods `405` |
| P2.7 | Validate public management API access | Create, inspect, disable and stats work without API credentials; safe errors remain |
| P2.8 | Validate and checkpoint the baseline | Greenfield scenario with requirement, decomposition, execution, tests, and engineer review |

This baseline deliberately has no expiry, Redis, or detailed analytics implementation. Do not make a synchronous aggregate counter a temporary dependency of redirect resolution. Add the event implementation in P4, then demonstrate the complete end-to-end prototype.

Test URL validation, injected randomness, forced collision races, idempotency concurrency, rollback, headers, HEAD behavior, token failures, and retained mapping state across restart. Database-sensitive tests must use PostgreSQL and real migrations.

**Exit gate:** Create → inspect → GET/HEAD → disable works through HTTP. Save a baseline commit identifier in `docs/scenarios/greenfield.md`; this is the input to the brownfield enhancement.

**Observed status:** P2.1–P2.7 implemented and tested. Clean verification passed 54 unit + 22 PostgreSQL integration tests (including 15 core integration cases), with zero failures/errors/skips. P2.8 scenario execution and checkpoint evidence are in [greenfield](scenarios/greenfield.md); human review remains pending. API definition for this checkpoint: [openapi-phase2.yaml](openapi-phase2.yaml). See [validation results](validation-results.md) for Compose outcomes and limitations.

## 6. Phase 3 — Demonstrate the brownfield expiration enhancement

**Goal:** Add expiry to the existing codebase without breaking its stored links.

1. Record the P2 baseline and map impacts: DTO/OpenAPI → validation → service → SQL/schema → redirect lifecycle → metadata → tests.
2. Add V2 with nullable `expires_at` and the documented constraint; do not rewrite applied V1.
3. Extend new creation and metadata with optional UTC expiry. Replay an existing idempotency result before applying new-creation expiry checks.
4. Check expiration on both GET and HEAD: before the boundary redirects; at/after it returns `410`.
5. Update SQL projections, error contracts, documentation, and regression tests.
6. Capture the before/after diff, generated/edited/rejected AI output, migration review, and validation in `docs/scenarios/brownfield.md`.

**Exit gate:** Apply V1, seed representative baseline rows, apply V2, restart, and prove old links remain unchanged and usable. Future expiry works; past/malformed expiry is rejected. Existing core and concurrency tests still pass. Caching is not introduced in this phase.

**Observed status:** Steps 1–6 implemented. Clean verification passed 76 unit + 28 PostgreSQL integration tests, with no failures/errors/skips. V1-seeded upgrade, legacy-key replay, exact GET/HEAD boundaries, precision/range and existing races/rollback verified. Packaged upgrade/restart results and before/after evidence are recorded in [brownfield](scenarios/brownfield.md) and [validation results](validation-results.md). Current contract: [openapi-phase3.yaml](openapi-phase3.yaml). Human migration/product acceptance remains pending.

## 7. Phase 4 — Resolve analytics ambiguity and implement bounded async recording

**Goal:** Define what a click means, then implement the contract without making redirects wait for analytics SQL.

### Contract and tasks

| Task | Work | Output / acceptance |
| --- | --- | --- |
| P4.1 | Record ambiguity: GET attempts vs people, prefetch, HEAD, UTC, retention, delivery loss | `docs/scenarios/ambiguous.md` with assumptions and rejected alternatives |
| P4.2 | Add V3 `redirect_events` migration and indexes | UUID event ID, link FK, observation timestamp; no IP/user-agent collection |
| P4.3 | Add `ClickEventPublisher`, named executor, and transactional recorder | Immutable payload; timestamp captured at resolution; no request objects captured |
| P4.4 | Submit valid GET events and exclude all other outcomes | HEAD, missing, inactive, expired, and lookup failures do not enqueue |
| P4.5 | Add stats aggregation and validated UTC date windows | One query yields buckets; total derived from the same result; empty buckets filled |
| P4.6 | Add bounded retention cleanup for events and idempotency records | Query-enforced retention plus short cleanup transactions |
| P4.7 | Add failure, queue, lag, and shutdown instrumentation/tests | Rejections and writer failures remain separate from redirect outcomes |

Use two fixed workers, 1,000 queued tasks, and a five-second maximum queue wait per replica initially; these are tuneable implemented settings. Use rejection that throws, catch it at the publisher, meter loss, and still redirect. Never use caller-runs, blocking queue admission, an unbounded queue, or waiting on futures. The worker opens its own transaction and observes failures including commit. Initially share the eight-connection application pool; worker concurrency constrains background work but does not reserve request connections.

Stats disclose `recordingMode: "best-effort-async"` and `eventuallyConsistent: true`. They include only committed retained events. Immediate stats can lag; queued events may be lost on overload, timeout/age rejection, or crash. Persisted history survives ordinary restart; pending memory does not. Record unknown commit outcomes separately; do not blindly retry them.

Coordinate shutdown: drain HTTP requests, stop event admission, drain accepted tasks within the remaining shared ten-second budget, then close the datasource. Do not assume default bean destruction provides this ordering.

**Exit gate:** With a paused worker, redirect returns without waiting for SQL and immediate stats may lag. Resume it and use bounded polling to verify event commitment. Deterministically test queue saturation, age drops, insert/commit failure, UTC boundary timestamps, retention, and shutdown. Record a hard-stop pending-event-loss demonstration and engineer review.

**Observed P4 status:** P4.1–P4.7 implemented; 92 unit + 40 PostgreSQL integration tests and the saved-P3/V2 → P4/V3 Docker upgrade/persistence check passed. Paused-worker HTTP lag, queue saturation, exact monotonic age limit, write/unknown-commit failure, UTC observation and query boundaries, bounded retention and concurrent key replacement, actual HTTP-first graceful drain and real hard-stop pending loss are verified. See [ambiguity scenario](scenarios/ambiguous.md) and [validation results](validation-results.md). Human semantics/diff acceptance remains pending.

## 8. Phase 5 — Add the scalable local deployment

**Goal:** Demonstrate shared mappings and consistent policies across two replicas behind NGINX.

| Task | Work | Output / acceptance |
| --- | --- | --- |
| P5.1 | Add `RedirectCache` with Redis and no-op adapters | Cache-disabled baseline remains runnable; versioned typed serialization |
| P5.2 | Implement cache-aside and absolute freshness | Primary read time + 60 seconds, capped by expiry; `PXAT`; no hit-based renewal |
| P5.3 | Handle lifecycle races and strict mode | Commit disable before best-effort invalidation; delayed fill retains original deadline; strict mode checks primary |
| P5.4 | Add bounded Redis waits, breaker, coalescing, and DB fallback | Cache errors do not cause unbounded DB work; exhausted fallback returns controlled `503` |
| P5.5 | Build one multi-stage application image and two configured instances | Same code/config policy; independent instance IDs and local analytics queues |
| P5.6 | Add NGINX upstream and the full Compose stack | Least connections; no sticky sessions or redirect response caching |
| P5.7 | Gate startup and designate cleanup ownership | PostgreSQL healthy → migrate completes → replicas ready → ingress ready; cleanup enabled only on app-1 |
| P5.8 | Add real Redis and cross-replica tests | Shared cache state, deadlines, corrupt values, eviction/restart, and routing |

The target stack has five long-running services—NGINX, app-1, app-2, Redis, PostgreSQL—and one migration job. Add V3 to the migration chain before this topology starts. Only migration receives schema-change credentials; runtime replicas do not run migrations. Expose only NGINX on localhost by default.

Add a local-demo `X-App-Instance` header and corresponding logs to prove distribution. Each app has two event workers and an eight-connection pool: four event transactions can run concurrently and total runtime connections can reach 16, excluding migration/admin clients. Scale replica counts only with a revised database budget.

NGINX passive failure handling is not continuous readiness polling. Test upstream name resolution/reload after container replacement and document the pinned image's behavior. Coordinate the migration/health dependencies explicitly.

**Exit gate:** Demonstrate creation handled by one replica and resolution by the other, eventual stats visible through either, warm cache read reduction, strict mode, expiry, and cached disable convergence. Test delayed old-read refill and invalidation failure. Stop each replica and Redis independently; record recovery/error behavior. Queues are disposable, and the single host/proxy/database/cache are failure points. Rate limiting is not part of this gate.

**Observed P5 status:** P5.1–P5.8 implemented. Real PostgreSQL/Redis tests verify sharing, warm mapping SQL reduction, strict mode, original absolute deadlines, corrupt/evicted/restarted values, paused refill, failed invalidation, expiry and bounded fallback/coalescing. Compose routing and recovery results are recorded in [validation results](validation-results.md); performance and human acceptance remain pending.

## 9. Phase 6 — Verify reliability, security, and measured scalability

**Goal:** Establish evidence for behavior under realistic failure and load rather than infer capacity from topology.

| Task | Work | Required evidence |
| --- | --- | --- |
| P6.1 | Exercise database outage, lock/query/network waits, and pool exhaustion | Bounded errors for DB-dependent work; fresh cached behavior reported separately |
| P6.2 | Test analytics saturation, slow writes, graceful shutdown, and abrupt termination | Redirect continuity, queue lag/drop metrics, committed vs pending persistence |
| P6.3 | Review database secrets, URL policy, SQL binding, and forwarded headers | Safe errors/logs; NGINX controls trusted forwarded metadata |
| P6.4 | Verify app/database restart and cleanup-owner recovery | Committed state survives retained volume; delayed cleanup catches up |
| P6.5 | Add k6 script and repeatable load instructions | Same dataset/hardware and explicit configuration for comparisons |
| P6.6 | Run build, integration, dependency, and review gates | Actual commands, failures/fixes, results, and human disposition |

Compare one replica without caching, two without caching, and two with warm Redis. Seed 10,000 links; do not follow external redirect destinations. Include hot-key, cold-cache, Redis-outage, and slow-analytics variants. Initial healthy target: 100 requests/second for 60 seconds, p95 server latency below 150 ms, less than 1% unexpected HTTP errors, and zero measured analytics drops. This is a target, not an assignment requirement or achieved result.

Record request counts, per-instance distribution, cache ratio, mapping SQL reduction, persisted event throughput, executor backlog/age, ingestion lag, drop reasons, database connections/resources, and JVM/container usage. Drain queues before healthy-run count reconciliation. Report unknown commit outcomes and loss separately. Fast redirects with lost analytics are not sufficient evidence of healthy throughput.

Use deterministic coordination for concurrency tests and bounded polling for eventual results. Mocks cannot prove PostgreSQL races, Redis expiry, or NGINX routing. CI must fail clearly when required container-backed checks cannot execute.

**Exit gate:** Applicable tests and blocking quality checks pass; serious findings are fixed or explicitly recorded with an engineer-reviewed disposition. Targets that fail remain failed in the report. No high-availability, exact-count, or production-readiness claim is inferred from a local run.

**Observed P6 status:** P6.1–P6.6 implemented. The clean build passes 99 unit + 54 real PostgreSQL/Redis integration tests. The 122-package indexed advisory gate passes after coordinated patch upgrades; no finding is waived. A pinned k6 harness seeds 10,000 links and records the seven 100 requests/second, 60-second cases, actual SQL/cache/event counters, sampled resources and bounded post-load reconciliation. Interrupted windows and earlier percentile-grouping issues are retained with rerun evidence. Healthy targets and fault failures are distinguished in [performance results](performance/phase6-final/results.md) and [review](performance/source-review.md). Hosted CI, native-container auditing, clean-checkout handoff, production sizing/HA and final human acceptance remain outside this evidence.

## 10. Phase 7 — Complete the handoff and assignment evidence

**Goal:** Make the submission reproducible and defensible for a reviewer unfamiliar with the conversation.

| Deliverable | Completion check |
| --- | --- |
| Application and build | Clean checkout builds and runs with documented prerequisites |
| `openapi.yaml` and migrations | Match actual endpoints, statuses, headers, schema, and migration history |
| `README.md` | Explains credentials/configuration, startup, sample API flow, tests, shutdown, retained-volume behavior |
| `docs/scenarios/greenfield.md` | Baseline task decomposition, implementation, AI disposition, validation, checkpoint |
| `docs/scenarios/brownfield.md` | Impact map, additive upgrade, before/after references, regression evidence |
| `docs/scenarios/ambiguous.md` | Ambiguities, selected semantics, alternatives, eventual/loss behavior, validation |
| `docs/ai-execution-log.md` | Actual generated/edited/rejected outputs with rationale, results, and engineer review |
| `docs/validation-results.md` | Environment, exact commands, pass/fail outcomes, load data, unresolved findings |
| `docs/engineering-summary.md` | Plan/rationale, delivered artifacts, assumptions, decisions, risks and limitations |
| Architecture/decisions/diagrams | Reconciled with implementation; proposals and completed behavior clearly distinguished |

Replay setup against an isolated clean environment, not by deleting an existing developer volume. Verify migration failure handling and GET/HEAD semantics, then demonstrate create → redirect → eventual stats → disable/convergence → restart persistence. Document how to stop services without deleting committed data.

**Observed P7 status:** Finished summary, requirement-by-requirement README, scenario evidence, reconciled architecture/decisions, reviewer checklist and a reproducible clean-source replay are delivered. The replay passed 99 unit + 54 integration tests, full topology lifecycle/retained restart, checksum-failure startup blocking and non-destructive fixture recovery; source/image identity and cleanup are recorded in [handoff evidence](handoff/README.md). Human final review remains pending, so the complete human-review exit gate is not claimed.

**Exit gate:** The runnable prototype, three complete scenarios, reproducible setup, required verification, and human review evidence all exist. Record incomplete work explicitly; documents alone do not complete the assignment.

## 11. AI use and human oversight throughout the phases

For each task, provide AI with intent, current code/schema, constraints, acceptance criteria, and the required validation. Keep prompts bounded to the impacted modules. The engineer inspects generated code, executes checks, and records the disposition.

| Engineering activity | Planned use | Evidence |
| --- | --- | --- |
| Implementation | Generate focused DTO/repository/service changes | Reviewed diff and tests |
| Debugging | Explain a reproduced compiler/test/runtime failure | Original failure, revised hypothesis, fix and rerun |
| Refactoring | Improve a concrete duplication or dependency problem | Preserved behavior and regression results |
| Tests | Propose meaningful boundary/race/failure coverage | Engineer-selected cases and executed results |
| Documentation | Draft setup/contracts from actual implementation | Clean setup replay and code/document agreement |
| Review preparation | Summarize risk, migration impact and unresolved findings | Engineer corrections and final review |

Do not manufacture rejected suggestions or successful validation records. Capture real iterations. Redact credentials and confidential/customer data from prompts and logs. Record explicit human sign-off for high-impact changes such as destructive migrations, public deployment, API access policy changes, or retention changes; none is implied by this plan.

## 12. Deferred scope and completion checklist

Optional future work includes client rate limits, a graphical UI, custom aliases, accounts/ownership, durable analytics messaging, batched consumers, aggregates/partitioning, hosted deployment, multi-host orchestration, and tested backup/failover. None should block the required prototype unless scope is explicitly changed.

- [x] Core APIs, analytics, and reliability behavior work end to end.
- [x] PostgreSQL is used for runtime and database integration validation.
- [x] V1 → V2 demonstrates a real brownfield upgrade; V3 adds event storage.
- [x] Async events have bounded admission, explicit eventual stats, and disclosed loss semantics.
- [x] NGINX routes to both replicas; shared Redis caching and lifecycle convergence are demonstrated.
- [x] Each of the three scenarios contains decomposition, execution, and validation.
- [x] Quality gates, failure checks, and measured load results are recorded accurately.
- [x] Actual AI dispositions, user choices and assistant review are traceable.
- [ ] Final human review and acceptance are recorded.
- [x] Setup works from a clean committed-source archive and final documents match the delivered system.
- [x] Remaining risks, failed targets, and production limitations are disclosed.

**Classification: Schwab Internal**
