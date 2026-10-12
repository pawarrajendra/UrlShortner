# Engineering Summary

The delivered prototype shortens HTTP/HTTPS URLs, preserves immutable mappings in PostgreSQL, supports optional expiration and irreversible disabling, and reports retained redirect observations. Java 21/Spring Boot 4.1.0, PostgreSQL, Redis, NGINX and Docker Compose follow the user's technology choices. All HTTP APIs are public at the user's explicit request. Phase 7 supplies the submission handoff and clean-source replay; final human acceptance remains pending in [the review record](handoff/review.md).

## Implementation and rationale

The work proceeded from contracts and a runnable database foundation to core lifecycle APIs, an additive expiration upgrade, bounded asynchronous analytics, shared caching/two replicas, failure/load validation and reproducible handoff. [The implementation plan](implementation-plan.md) records the dependencies and [the decision records](decisions.md) explain alternatives.

PostgreSQL makes mapping/code uniqueness, atomic link/idempotency creation, lifecycle persistence and UTC aggregation enforceable with one storage system. Runtime and migration roles are separate. V1 creates mappings/keys, V2 adds nullable expiration without replacing old rows, and V3 adds events. Applied migrations remain immutable. The one-shot Flyway job validates history before applications start; replicas verify compatible schema without DDL credentials. H2 was superseded by the user's PostgreSQL choice.

Eight-character random Base62 codes are immutable and retained after disable/expiry. Conflicts retry within explicit limits in fresh transactions; uncertain database outcomes are not blindly retried. Optional global idempotency keys preserve the original creation result for 24 hours, allowing recovery from a lost response. Metadata reflects current lifecycle state. GET/HEAD resolution returns temporary no-store redirects; HEAD and unsuccessful resolutions do not record observations.

Redis caches mappings using the original primary-read timestamp and an absolute deadline capped at expiry and 60 seconds. Hits do not renew freshness. Disabling commits before best-effort invalidation; delayed refills can briefly serve an old mapping within that bound. Strict mode resolves from PostgreSQL. Redis failure uses bounded primary fallback, coalescing and a short breaker rather than unconstrained database pressure. NGINX routes to two stateless replicas; only its localhost port is published. This single-host topology demonstrates distribution, not production high availability.

Analytics count active GET resolution attempts, including bots/retries, rather than people or verified browser visits. Each replica has two workers and a 1,000-event queue with a five-second maximum queue age. Workers commit independently of the redirect response. Queue overload, write failure, shutdown expiry and crashes can lose events. Unknown commit outcomes are disclosed and not retried. UTC stats contain committed observations retained for 30 days; default ranges cover seven calendar days and explicit ranges span at most 30. Maintenance runs only on app-1. Graceful shutdown drains HTTP before closing analytics admission and shares one ten-second budget with event drain/pool teardown.

## Assignment evidence

| Requirement | Delivered evidence |
| --- | --- |
| Working core APIs, analytics, reliability | [OpenAPI](../openapi.yaml), Java source, V1–V3 migrations, PostgreSQL/Redis integration tests and disposable full-stack replay |
| Greenfield decomposition/execution/validation | [Core-service scenario](scenarios/greenfield.md), saved P2 checkpoint and code/key race/rollback tests |
| Brownfield reasoning | [Expiration scenario](scenarios/brownfield.md), saved P2/P3 revisions, unchanged V1, nullable V2 and real retained-volume upgrade evidence |
| Ambiguous requirement handling | [Analytics scenario](scenarios/ambiguous.md), selected semantics, rejected alternatives, eventual/loss tests and observed slow-writer losses |
| Maintainability and contracts | Modular controllers/services/JDBC repositories, validated configuration, style gates, [schema plan](schema-plan.md), staged/current API definitions |
| AI across engineering activities | [AI execution log](ai-execution-log.md) records implementation, debugging, refactoring, test/document generation and review preparation with actual dispositions |
| Setup and engineering handoff | [README](../README.md), [handoff guide](handoff/README.md), clean committed-source replay, exact validation records and this summary |
| Quality gates and human oversight | Blocking Maven/style/integration/advisory gates, disclosed failures, assistant review and a separate pending human [review record](handoff/review.md) |

## Observed validation

The Phase 6 clean build passed **99 unit and 54 real PostgreSQL/Redis integration tests**, with zero failures/errors/skips. Coverage includes strict inputs, collisions/key races, rollback, expiry boundaries, UTC/retention, queue saturation/age, graceful HTTP-first shutdown, hard-stop pending-event loss, cache deadline/refill races, outages, pool exhaustion, row locks, query cancellation and cleanup recovery. [Validation results](validation-results.md) retain commands, environment and separate phase evidence. Phase 7 repeats the full build from exported committed source and checks the documented setup/lifecycle in an isolated stack; its exact outcome and source/image identity are in [the replay report](handoff/phase7-replay/report.json).

At 100 requested redirects/second for 60 seconds, the three healthy topology comparisons passed the recorded latency/error/scheduling/event gates. Warm caching reduced mapping SQL from 6,000 to 86 (about **98.6%**). Hot-key, cold-cache and Redis-outage cases missed scheduled arrivals. The slow-writer case returned 6,001 successful redirects but committed only 70 observations: **5,931 were lost or failed**. These failures remain visible in [the measured results](performance/phase6-final/results.md); this is neither a maximum-capacity measurement nor a linear-scaling claim. Interrupted earlier windows and corrected measurement/report iterations are retained.

The indexed Maven-advisory scan initially found 19 package/advisory pairs. Coordinated patch upgrades removed those findings; the final recorded scan covers 122 resolved artifacts with zero indexed findings. Its dated evidence does not certify native container images, build plugins or all security risks. Hosted CI is configured but has not been executed in this environment.

## Assumptions, limitations and review

Product confirmation remains pending for the global 24-hour idempotency window, optional immutable expiry, observed-GET analytics, 30-day retention and cached disable freshness. Public API access means any reachable caller can create, inspect, disable and query links. There are no accounts, ownership, OAuth or client rate limits. URL validation does not perform DNS/reputation checks or fetch destinations.

The request pool is shared with workers; database capacity bounds all replicas. Best-effort events can undercount; `asOf` is not an ingestion-completeness watermark. NGINX passive failover can produce transient gateway errors. Redis, PostgreSQL, NGINX and the host remain individual failure points. A retained volume is not a backup. TLS hosting, restore/failover drills, durable event transport, aggregate/partitioned analytics, native-image auditing and production sizing remain future work.

AI generated and edited code, tests and documents; assistant checks do not substitute for engineer acceptance. Explicit user choices and the public-access change are recorded separately from unconfirmed product defaults. No human rejection or sign-off is invented. [The final review record](handoff/review.md) provides the concrete artifact/evidence checklist and preserves outstanding acceptance. No remote deployment or push is part of this handoff.
