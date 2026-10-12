# Architecture Decision Records

These records accompany [architecture.md](architecture.md) and [problemStatement.md](problemStatement.md). They retain decision history and describe the implemented prototype plus deferred alternatives. Updated October 11, 2026. See [validation evidence](validation-results.md) and [final review](handoff/review.md).

**Status:** "Accepted - user selected" identifies an explicit technology preference. "Implemented" identifies assistant-verified behavior; it does not imply human acceptance. Superseded records preserve historical choices; production alternatives remain proposals. Production evolution is outside the current prototype scope.

## ADR-001: Java and Spring Boot

**Status:** Accepted - user selected.

**Context:** The user requested a Java/Spring Boot technology stack for the 2-3 day URL-shortener assignment.

**Decision:** Use Java 21 LTS, Spring Boot 4.1.x, Spring MVC, embedded Tomcat, and Maven Wrapper. Pin a compatible Boot patch and resolve the full dependency set during implementation. Use Boot dependency management for supported libraries.

**Alternatives:** The original TypeScript/Fastify proposal; a bare Java HTTP server; a reactive Spring WebFlux application.

**Rationale:** Matches the requested stack and supplies dependency injection, validation, testing, and executable packaging. Synchronous MVC fits the small JDBC-backed service; reactive code adds complexity without a demonstrated need.

**Consequences:** Use Java DTOs, services, JDBC repositories, JUnit, MockMvc, and Maven verification. Do not add Spring AI merely because the assignment calls for AI-assisted engineering: AI accelerates the engineering work, rather than serving end-user requests.

**Validation:** Resolve dependencies, compile, run the test suite, start the JAR, and exercise the HTTP endpoints. Current compatibility is supported by [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html).

## ADR-002: Embedded in-memory H2 (superseded)

**Status:** Superseded by ADR-011. Retained as historical rationale; H2 is no longer the selected runtime or integration-test database.

**Context:** The user requested an in-memory H2 database. The assignment asks for a working prototype with reliability features and production-minded reasoning, but does not mandate a storage vendor or restart durability.

**Decision:** Use a named embedded database, `jdbc:h2:mem:urlshortener`, with `DB_CLOSE_DELAY=-1`. One JVM is the deployment boundary; no H2 TCP server or database container. Retain relational constraints and transactions.

**Alternatives:** PostgreSQL; H2 file mode; Java concurrent maps.

**Rationale:** Removes external database setup and keeps the demo reproducible while allowing SQL-based modeling, uniqueness, and transaction tests. Concurrent maps would require hand-built integrity and transaction semantics.

**Consequences:** All links, event history, disable history, and idempotency records are lost at JVM exit. The delay setting only protects against temporary last-connection closure; it does not persist data. Separate JVMs do not share this database. Code non-reuse and idempotency guarantees are limited to the database session. Heap and database capacity must be monitored and capped. Startup migrations rebuild schema, not prior data.

**Validation:** Verify multiple pooled connections see the same data, temporary connection closure does not erase it, JVM restart does erase it, and concurrent uniqueness/rollback behavior is correct. Verify test database isolation and cleanup. See [H2 in-memory behavior](https://h2database.com/html/features.html#in_memory_databases).

**Revisit when:** Links must survive crashes/restarts, multiple instances must share mappings, backup/restore becomes required, or data exceeds the measured heap budget. Adopt persistent storage with vendor-specific integration tests; switching a JDBC URL alone is not sufficient validation.

## ADR-003: Modular monolith

**Status:** Implemented and locally verified; final human acceptance pending.

**Context:** The project is a short, engineer-led assignment with a single application and a PostgreSQL server.

**Decision:** Keep controllers, services, domain policies, repositories, configuration, and observability in separate packages within one application.

**Alternatives:** Microservices; logic embedded directly in controllers.

**Rationale:** Clear dependencies and testable boundaries without network coordination or deployment overhead. Inject clock, randomness, and persistence adapters for deterministic tests.

**Consequences:** One deployment and shared failure domain. No claim of high availability. Domain code must avoid servlet and JDBC concerns.

**Validation:** Review package dependencies and verify service unit tests without a Spring server. Full HTTP/database tests verify composition.

## ADR-004: Spring JDBC and explicit transactions

**Status:** Implemented and locally verified; final human acceptance pending.

**Context:** The small schema needs unique codes, atomic idempotent creation, and inspectable analytics queries.

**Decision:** Use Spring JDBC with parameter binding and HikariCP, rather than JPA. Use `TransactionTemplate` for creation attempts, with a fresh transaction for each retry. Use named database constraints to distinguish conflicts.

**Alternatives:** Spring Data JPA/Hibernate; raw JDBC; Spring Data JDBC.

**Rationale:** Explicit SQL and transaction boundaries make collision/idempotency behavior easier to explain and test. The design does not need complex object graphs or lazy entity loading.

**Consequences:** Maintain row mappers and SQL manually. Use PostgreSQL SQL and explicit named-constraint handling for the creation workflow. Failed transactions must be rolled back before another attempt; catching an exception inside a rollback-only transaction is insufficient. Event insertion must be separate from mapping resolution and creation.

**Validation:** Test concurrent same-key requests, conflicting payloads, forced code collisions, candidate rollback, and timestamp round trips using PostgreSQL Testcontainers and committed transactions.

## ADR-005: Flyway owns schema initialization

**Status:** Implemented and locally verified; final human acceptance pending.

**Context:** The assignment requires a brownfield enhancement and reviewable schema changes, with durable storage and compatibility with existing rows.

**Decision:** Use numbered Flyway SQL migrations in one non-web migration job before replica readiness. V1 creates links/idempotency; V2 adds nullable expiration; V3 adds observed events. Disable competing SQL initializers and do not add Hibernate automatic DDL.

**Alternatives:** A single `schema.sql`; ORM-generated schema; manual console changes.

**Rationale:** Migrations preserve the history of changes and let tests demonstrate compatibility from the greenfield baseline.

**Consequences:** Existing PostgreSQL databases preserve rows and migration history; the migration job applies only pending changes before replica readiness. Tests apply V1, insert baseline data, apply V2, restart the application, and verify old rows still resolve. Destructive migration and rollback behavior require explicit review. Use the Flyway PostgreSQL module and separate hosted migration credentials.

**Validation:** Test a fresh migration chain, V1-to-V2 upgrade, and failure on invalid migration. See [Spring Boot initialization guidance](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## ADR-006: Random codes and temporary redirects

**Status:** Implemented/tested in P2/P3, including expiry; cached behavior is implemented in P5 under ADR020. Human acceptance pending.

**Context:** Links must be short, resolve predictably, and honor disabling and optional expiration.

**Decision:** Generate eight-character Base62 codes with `SecureRandom`; enforce uniqueness in PostgreSQL and bound retries. Use GET/HEAD `302` redirects with `Cache-Control: no-store`. Keep destinations immutable and retain disabled mappings across restarts.

**Alternatives:** Sequential IDs; custom aliases; permanent `301` redirects; mutable destinations.

**Rationale:** Random identifiers are simple to generate without sequence exposure. Uncached temporary HTTP responses let each request reach the app for lifecycle checks; the Redis mapping-cache policy is governed separately by ADR-013.

**Consequences:** Codes remain guessable public identifiers. Collisions are possible; database enforcement is mandatory. HEAD needs an explicit handler so it does not count as a GET. An already-resolved in-flight request may complete after a disable commit. Cached mode also allows the bounded stale-state window in ADR-013; strict mode checks PostgreSQL directly. The persistent code registry survives ordinary restarts; deleting data or restoring an old backup may invalidate historical non-reuse.

**Validation:** Test code format/collisions, Location and cache headers, explicit HEAD behavior, expiry boundaries, disabling, and unknown codes. See [HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110.html).

## ADR-007: Best-effort request analytics

**Status:** Product semantics retained; synchronous recording superseded by ADR-015.

**Context:** "Analytics" does not specify unique visitors, successful browser navigation, bot exclusion, retention, or recording guarantees.

**Decision:** Count GET attempts considered active under the selected cache consistency policy, including mapping-cache hits; exclude HEAD, missing/inactive links, and rejected requests. Store raw events for 30 days in PostgreSQL. Produce UTC daily buckets and a total derived from the same query result. Submit an event without waiting for persistence; bounded asynchronous recording in a separate worker transaction is defined in ADR-015. Rejection or recording failure does not prevent redirect.

**Alternatives:** Exact unique-user analytics; durable asynchronous queue; counters alone; requiring event persistence before redirecting.

**Rationale:** Small, inspectable implementation with explicit semantics. Prioritizes redirect availability over complete analytics.

**Consequences:** Events can be lost, bots/prefetch can count, and a recorded event does not prove response delivery or destination navigation. Recorded events survive ordinary restarts. The bounded in-memory queue can lose pending work on overload or crash; statistics lag until events commit. Shared pool and background JDBC work limit failure isolation. PostgreSQL statement/lock limits, pool acquisition limits, and network timeouts cover different failure modes; they are not a proof of a hard response deadline. Recording failures must produce metrics.

**Validation:** Test HEAD exclusion, failure/lock timeout behavior, UTC boundaries, empty buckets, total consistency, retention disclosure, and restart persistence. Document these semantics in API responses and the final summary.

## ADR-008: Public HTTP API access

**Status:** User-requested scope revision on 2026-10-11.

**Decision:** Creation, metadata, disable, stats, redirects and health endpoints require no application credential. Keep destination/body validation, safe errors, resource bounds, private infrastructure networking and separate runtime/migration database roles.

**Rationale:** The assignment does not specify an identity system. The user explicitly requested public APIs across the project.

**Consequences:** All callers who can reach the service can create links, read metadata/statistics and disable known codes. No account or ownership model is introduced. The default Compose ingress remains bound to localhost. Idempotency keys share a global namespace; retain the historical digest prefix for compatibility with persisted keys.

**Validation:** Exercise every API without credential headers, verify request validation/lifecycle/errors, and retain PostgreSQL least-privilege checks.

## ADR-009: Portable rendered diagrams

**Status:** Accepted - requested rendering fix.

**Context:** Mermaid diagrams did not appear in the user's Markdown viewer.

**Decision:** Embed rendered PNG component and redirect-sequence diagrams, retain a reproducible Python drawing script, and include prose equivalents. Use repository-relative image paths in architecture.md and include plain-text diagrams as a fallback for viewers that block local images. Assets are stored together under `docs/diagrams/` for portability.

**Alternatives:** Rely on viewer Mermaid support; link to an external diagram service; use only text diagrams.

**Rationale:** PNGs render without Mermaid plugins or network requests and can be reviewed directly.

**Consequences:** Update both the document and assets when flows change. Keep the `docs/diagrams/` directory with the Markdown document when relocating the repository. Plain-text diagrams remain readable without image or Mermaid support.

**Validation:** Render both images, inspect legibility and arrows, and verify the Markdown image targets exist.

## ADR-010: Engineer-led AI assistance and acceptance

**Status:** Required workflow implemented across P0–P7; human final acceptance pending.

**Context:** The assignment evaluates disciplined AI acceleration and explicit engineer ownership.

**Decision:** Use bounded tasks with context, constraints, acceptance criteria, and meaningful validation. Keep a traceability log of generated/edited/rejected output and rationale. The engineer owns decomposition, reviews changes, and signs off on high-impact actions.

**Alternatives:** Autonomous runtime orchestration; accepting generated output without review.

**Rationale:** Directly aligns with the assignment and makes decisions defensible.

**Consequences:** Record prompts safely, review diffs, run verification, and separate actual measured results from intended targets. No runtime LLM integration is necessary.

**Validation:** All three scenario records must contain decomposition, execution, validation, AI disposition, and review evidence. The final summary must report restart-persistence results, analytics loss semantics, and unresolved production availability/recovery gaps.

## ADR-011: PostgreSQL as the runtime and integration-test database

**Status:** Accepted - user selected. Supersedes ADR-002.

**Context:** After comparing H2, PostgreSQL, and Cassandra, the user selected PostgreSQL. A URL shortener does not inherently require a relational database; the choice is based on this assignment's timebox, integrity, analytics, and reliability demonstration.

**Decision:** Use PostgreSQL 18 through the PostgreSQL JDBC driver and Spring JDBC. Run locally with Docker Compose and a named persistent volume. Use Flyway PostgreSQL migrations and PostgreSQL Testcontainers of the same major version for database-backed tests. Pin the image patch/digest and compatible dependency versions during implementation.

**Alternatives:** H2 in-memory (simple but loses mappings on JVM restart); H2 file mode (persistent embedded demo); Cassandra (valid partition-key lookup model, with query-specific analytics tables and additional distributed consistency/operational decisions).

**Rationale:** Unique constraints protect codes; transactions make link/idempotency creation atomic; SQL supports UTC event aggregation; separate database operation allows realistic outage and restart tests. For the small prototype, these features simplify review and validation. Scale alone is not a reason to reject or adopt Cassandra.

**Consequences:** Docker or another PostgreSQL installation is required. Database credentials, connection budgets, disk growth, and migration roles need configuration. Committed state survives app/database restart only when storage is retained. Removing volumes or losing storage can still lose data. Local persistence does not establish high availability or validated backup recovery. H2 is not a substitute for PostgreSQL integration tests.

**Local setup contract:** Start the full Compose topology in ADR-012. Keep PostgreSQL internal by default, preserve its named volume, and never reset data on ordinary startup. Separate initialization, runtime, and migration credentials. The one-shot migration job applies pending schema changes before either replica becomes ready.

**Validation:** Create a link, record a redirect, and inspect analytics. Restart the app and verify the same state. Restart the database with its volume retained and verify recovery. Test simultaneous code/idempotency conflicts, rollback after errors, V1-to-V2 migration of existing rows, database outage/readiness, lock and network timeouts, and UTC timestamp aggregation. Perform backup/restore testing before any production recovery claim.

**References:** [Spring Boot PostgreSQL/Flyway setup](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/), [official PostgreSQL container](https://hub.docker.com/_/postgres), [PostgreSQL timeout controls](https://www.postgresql.org/docs/current/runtime-config-client.html).

## ADR-012: Local horizontal scaling with NGINX and Docker Compose

**Status:** Implemented in P5 and measured in P6; clean setup replay in P7. Human final acceptance pending.

**Context:** The user requested a load balancer, distributed cache, and a design runnable locally through Docker.

**Decision:** Run five long-lived containers: NGINX, app-1, app-2, Redis, and PostgreSQL. Build one Java app image and run it twice. Add a one-shot migration job. Expose only NGINX at localhost:8080; keep dependencies private. Use least-connections routing with no sticky sessions or redirect response caching.

**Alternatives:** One app instance; cloud-managed ingress; Kubernetes; NGINX caching HTTP redirects directly.

**Rationale:** Demonstrates shared-state correctness and independent application restarts with a small local footprint. Cache policy, PostgreSQL, and Redis are shared across replicas.

**Consequences:** A single laptop does not demonstrate multi-host resilience or linear throughput scaling. Open-source NGINX passive detection is not continuous active readiness probing. Container replacement requires configured DNS resolution or upstream reload. Do not automatically replay possibly submitted management POST requests. Migrations run once before replicas start; only the migration job receives schema-change credentials.

**Validation:** Prove traffic reaches both replicas; create on one and resolve on the other; stop either app and measure failed requests/recovery; replace a container and verify name resolution; verify no host exposure of app/database/cache ports; run a clean startup and migration failure test. Compare load against one-replica and cache-disabled baselines.

**References:** [NGINX balancing](https://nginx.org/en/docs/http/load_balancing.html), [NGINX proxy controls](https://nginx.org/en/docs/http/ngx_http_proxy_module.html), [Compose health/completion dependencies](https://docs.docker.com/compose/how-tos/startup-order/).

## ADR-013: Redis cache-aside with explicit lifecycle consistency

**Status:** Implemented in P5, refined by ADR020. The 60-second disable freshness policy requires engineer product review; it is not an assignment requirement or an immediate-takedown guarantee.

**Context:** Shared caching reduces PostgreSQL mapping reads, but disabling and concurrent cache refill introduce stale-state risks.

**Decision:** Cache active immutable mappings at `redirect:v1:{code}`. Include the complete lifecycle fields and absolute `freshUntil` with original `readAt`. Read the PostgreSQL primary on a miss. The deadline is the earlier of original database statement time plus 60 seconds and link expiration. Populate with absolute Redis expiry and never extend freshness on a hit. Validate the embedded deadline and expiration in every app. Exclude missing/error responses from initial caching.

**Alternatives:** Memcached; per-replica caches; Redis as source of truth; strict per-request PostgreSQL checking; coordinated invalidation with stronger consistency.

**Rationale:** Redis provides a shared mapping cache; client rate limits are deferred. An absolute deadline limits delayed old-read fills without depending on Pub/Sub delivery or a distributed lock.

**Consequences:** Commit disabling in PostgreSQL, then best-effort delete the cache key. A concurrent old reader can refill after deletion: cached mode can serve stale state until the original deadline, plus clock-skew tolerance. Clocks require synchronization and validation. Strict mode bypasses mapping caching for database-observed disabling. Lost cache data is rebuilt; cache errors use bounded DB fallback. Redis availability does not establish PostgreSQL-independent availability. Cache hits still trigger best-effort GET analytics writes.

**Validation:** Warm/cold cache, TTL non-extension, exact expiry, delayed-fill/disable race, invalidation failure, Redis restart/eviction, corrupted cache values, clock-skew tests, and strict mode. Never silently assert immediate disabling in cached-mode tests.

**Reference:** [Redis conditional writes and absolute expiration](https://redis.io/docs/latest/commands/set/).

## ADR-014: Bounded fallback and singleton maintenance (scope revised)

**Status:** Revised during Phase 0. Earlier client-limit and Redis-dependent management-admission proposals are deferred, as clarified by the user and implementation plan.

**Context:** Cache failure must not overload PostgreSQL or duplicate retention work. Per-client quotas are a separate product scope decision.

**Decision:** Bound cache-miss/failure database fallback concurrency. Redis cache failure does not independently deny management routes. Enable retention scheduling only on app-1 in the local demo. Redis degradation is separate from readiness; PostgreSQL/schema readiness remains required. No shared client limiter, quota 429, or degraded client limiter is implemented in the baseline.

**Alternatives:** Unrestricted database fallback; every replica running cleanup; distributed scheduling; optional rate limits if separately requested later.

**Rationale:** Preserve required resource controls without promoting an unrequested client quota into scope.

**Consequences:** Overloaded fallback yields controlled dependency errors; cleanup pauses when app-1 is unavailable and catches up on return. Adding replicas increases aggregate connections/workers; local ownership is not production scheduler coordination.

**Validation:** Redis fallback saturation/recovery, management without Redis, cleanup-owner outage/recovery, and total pool budgets. ADR-015 defines async analytics bounds and loss.

## ADR-015: Bounded asynchronous click recording

**Status:** Implemented and locally verified in P4; replaces synchronous recording in ADR-007. Performance measurements are recorded in P6; human semantics acceptance remains pending.

**Context:** The reference implementation separates detailed click recording with async workers but retains synchronous aggregate counter updates. Our cache design should avoid waiting for analytics SQL while preserving explicit loss, resource, and consistency semantics.

**Decision:** Submit immutable GET events to a named per-replica executor with two fixed workers and 1,000 queued tasks initially. Return the redirect without waiting for SQL and maintain no synchronous per-link click counter. On full/closed admission, meter rejection and continue redirecting. Drop tasks older than the proposed five-second queue-wait limit. Workers use separate, bounded PostgreSQL transactions and the existing eight-connection pool; catch failures including commit. No prototype retry buffer is added. Stats count committed retained events and explicitly disclose eventual consistency. Only committed events survive restart.

**Alternatives:** Synchronous event insert; synchronous aggregate counter plus async detail; unbounded executor; caller-runs; durable broker and independently scaled consumers; transactional outbox.

**Rationale:** Removes analytics SQL wait from the redirect response, avoids hot-row counter contention, and contains burst memory/concurrency within the assignment timebox. A bounded queue controls admission but provides no durability or sustained throughput increase.

**Consequences:** Each replica holds lossy pending state. Queue overload, age drops, transaction errors, and crashes can undercount. Immediate stats can lag; `asOf` is not a completeness watermark. Two replicas allow four concurrent event transactions and 2,000 queued tasks; adding replicas increases database demand. Shared-pool contention remains. Shutdown drains requests then accepted events within one ten-second budget before closing the data source; hard termination can lose work without a final metric. No exactly-once or complete analytics claim is made. Future durable transport needs acknowledgements, retries, deduplication, batching, and an explicit failure policy; outbox persistence restores synchronous database work.

**Validation:** Deterministically fill/pause workers, verify redirects do not wait for event SQL, inspect rejection/age metrics, inject insert and commit failures, verify UTC timestamps and eventual cross-replica stats, reconcile healthy-run counts after queue drain, measure ingestion lag and persisted throughput, test graceful shutdown ordering, and kill a replica with pending events to demonstrate loss. Healthy load tests require zero measured drops; latency alone is insufficient.

**Reference:** [Spring task execution, bounded executors, and async proxy behavior](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).

## ADR-016: Phase 0 contract baseline and scope precedence

**Status:** Defaults implemented and locally verified through P1–P7; human product/contract acceptance pending.

**Context:** Phase 0 must define a concrete API/schema and resolve conflicting scope/staging before implementation. The edge catalog identified eight boundary-policy gaps.

**Decision:** Use [phase0-contracts.md](phase0-contracts.md) and [openapi.yaml](../openapi.yaml) as the drafted API/defaults baseline; use [schema-plan.md](schema-plan.md) for V1 links/key records, V2 expiry, V3 events. Rate limiting is optional/deferred. Resolve GAP-01 through GAP-08 with documented defaults for parsing/auth, URL normalization, idempotency, routing, microsecond timestamps, monotonic queue age, UTC ranges/retention, and server-generated tracing.

**Alternatives:** Leave boundaries to framework defaults; implement full final schema at the greenfield checkpoint; silently retain the unrequested limiter; claim human approval from artifact generation.

**Rationale:** Concrete contracts and incremental migrations make implementation/test expectations reviewable and preserve a genuine brownfield exercise. Deferred scope is explicit.

**Consequences:** Phase 0 produces artifacts, not a working prototype. Defaults remain assumptions until human review and implementation evidence. DTO definitions are in the OpenAPI/documents; buildable Java records begin after bootstrap. Static checks do not establish full OpenAPI conformance, SQL behavior, or production readiness.

**Validation:** Run `ruby scripts/validate-contract.rb`, verify local links/fences and diagram assets, and record actual results. P1–P7 supply runtime, upgrade, concurrency, failure, performance and setup evidence. Human review status remains separately recorded.

**Classification: Schwab Internal**

## ADR-017: Conditional stale-key replacement under existing V1 privileges

**Status:** Implemented and verified in P2; human acceptance pending.

**Context:** V1 key records grant SELECT/INSERT/DELETE, while PostgreSQL `SELECT ... FOR UPDATE` also requires UPDATE. Applied V1 is the baseline for the later additive brownfield migration.

**Decision:** Read key state normally. For an expired record, DELETE only where digest, observed creation timestamp and expiry cutoff still match. PostgreSQL locks and rechecks this predicate after a concurrent change. A zero-row delete rolls back/retries in a fresh transaction. Insert the replacement mapping/key in the same transaction; uniqueness races roll back candidates before recovery. Limit key-race retries to five independently of the five code-collision budget.

**Alternatives:** Grant UPDATE to key records; mutate applied V1; use advisory locks; unconditionally delete by digest.

**Rationale:** Preserve the established runtime role and migration history while preventing stale observations from deleting newly recreated keys. Different keys proceed independently. PostgreSQL remains the concurrency authority.

**Consequences:** Contention/timeouts can return controlled 503; there is no infinite retry or guaranteed response-time claim. Matching replay uses original immutable metadata, with null disabled state, while inspect reflects current disabling. Global key encoding retains its legacy prefix for persisted-key compatibility. P4 scheduled cleanup preserves these identity/time/current-cutoff predicates in bounded batches.

**Validation:** Real PostgreSQL matching/conflicting forced key races, losing-candidate rollback, exact 24-hour boundary, concurrent expired-key replacement and preserved link/key/disabled state across Compose restarts passed. See [validation results](validation-results.md).

## ADR-018: Additive immutable expiry and replay-compatible validation

**Status:** Implemented and verified in Phase 3; human migration/product acceptance pending.

**Decision:** Add nullable `expires_at` with V2 and a strictly-after-creation CHECK. Preserve V1 and all existing rows/keys. Accept explicit offset timestamps with at most six fractional digits and UTC years 0001–9999, without rounding. Capture creation time once at microsecond precision and compare/store that instant. GET/HEAD resolve active only before expiry; disabling takes precedence if both conditions apply. Retain expired codes and immutable destinations/expiry.

**Rationale:** Nullable additive storage preserves old non-expiring links. Strict precision avoids JDBC rounding across a lifecycle boundary. Canonical UTC expiry makes equivalent offsets share a fingerprint; null retains the exact P2 encoding. Matching keyed replay occurs before new-creation future checks, so response recovery remains possible after expiry or disabling.

**Alternatives:** Rewrite V1/backfill an artificial expiry; silently round timestamps; validate future time before key replay; delete expired mappings; add cache in the same phase. These would weaken compatibility or obscure the brownfield change.

**Consequences:** A very short link can expire before response delivery. An already-resolved request is not cancelled by clock passage. V2 has no new index or cleanup scheduler. Responses now include nullable `expiresAt`; clients enforcing the exact P2 response schema must adopt the P3 contract. Current migration target/readiness require V1+V2. Older P2 code ignores expiry, so local upgrade is coordinated; serving P2 after creation of expiring links is not a safe rollback. Existing roles/grants are retained. Backups, interrupted-DDL recovery and production rolling rollout remain unverified.

**Validation:** Saved P2 checkpoint, V1-seeded PostgreSQL upgrade, old null-expiry key replay, exact GET/HEAD boundary, disabled precedence, offset equivalence, precision/range rejection and short-lifetime regression. See [brownfield](scenarios/brownfield.md) and [validation results](validation-results.md).

## ADR-019: Explicit event execution, bounded cleanup and HTTP-first shutdown

**Status:** Implemented/tested in Phase 4; human diff/product review pending.

**Decision:** Use a named fixed `ThreadPoolExecutor` and throwing `AbortPolicy` behind `ClickEventPublisher`, with a separate `EventRecorder` transaction. Use immutable UUID/link/observation payloads and monotonic admission ticks. Meter rejected, aged and shutdown-pending work separately from write failures and unknown commit outcomes. Do not retry uncertain commits. Aggregate UTC stats once, derive totals from returned buckets, and disclose eventual/retained results.

**Rationale:** Explicit execution makes admission, age boundaries, failure handling and shutdown drain directly observable and testable. It avoids caller-thread SQL, unbounded memory and async self-invocation mistakes. One batch per table per interval bounds maintenance work; identity/time/current-cutoff DELETE predicates preserve V1 grants and concurrently recreated keys.

**Alternatives considered:** `@Async`/`ThreadPoolTaskExecutor` proxy, caller-runs, per-link synchronous counters, unbounded queue/retry, durable transport, `SELECT FOR UPDATE` requiring extra key UPDATE grants, cleanup loops that never yield, and default bean-destruction ordering. These were rejected for this phase's explicit bounds or compatibility; durable delivery remains a possible later requirement.

**Shutdown:** Start one ten-second monotonic budget at context close. Boot's HTTP graceful lifecycle runs before the analytics lifecycle. In-flight requests may still submit while HTTP drains; then stop admission/maintenance, drain accepted tasks within remaining time, discard pending tasks and interrupt workers at timeout, and close Hikari explicitly last. Compose grants 15 seconds for teardown. The Boot phase constant is deprecated but pinned to 4.1; a real HTTP test guards ordering during framework changes. No portable guarantee is made that every interrupted remote transaction stopped before pool close.

**Consequences:** The eight-connection pool is shared. No request reservation, physical-retention catch-up SLA, exactly-once count, unique visitor claim, complete `asOf` watermark or measured p95 is promised. SIGKILL loses pending observations without final metrics. Cleanup failure does not affect resolution, and delayed cleanup cannot widen stats retention. Metrics have bounded labels and are registered locally; exporter/operational exposure is later work.

**Validation:** Deterministic worker controls; exact five-second/plus-one-nanosecond age tests; real PostgreSQL insert failure and key replacement race; mocked commit transport failure to prove unknown-outcome classification; real in-flight HTTP graceful drain; committed data in a fresh context; test-only child JVM hard killed with one executing and one queued observation. See [validation results](validation-results.md).

## ADR-020: Shared mapping cache, bounded fallback and discoverable local ingress

**Status:** Implemented in P5; local validation in [validation results](validation-results.md). Measured comparisons are recorded in P6; maximum production capacity and human final acceptance remain unproven.

**Decision:** Add `RedirectCache` with no-op and Lettuce adapters; cache only active typed schema-1 mappings. PostgreSQL statement-start time anchors an absolute 60-second window capped by expiry. Redis `SET PXAT` floors to milliseconds; hits never renew. Disabled state commits before best-effort invalidation. Delayed old fills retain their original deadline. Strict mode always reads the primary. Metadata/stats remain primary reads.

**Rationale:** Shared acceleration reduces mapping SELECTs without becoming the durable store. Redis eviction/restart cannot erase links or committed analytics. Typed validation prevents corrupt/version-mismatched values being served. Exact SQL expiry remains correct when cache expiry is conservatively rounded early. Synchronizing app/DB/Redis clocks is still an operational prerequisite.

**Bounds:** Separate 100 ms connection/command waits; reject disconnected buffering, 64 pending commands and two I/O/computation threads each. Three failures trigger two-second cooldown with one recovery probe. Per-app mapping SQL admission is four; same-key cached misses share one result with 32 bounded followers and one-second waits. SQL/HTTP errors remain controlled; no fallback retry storm or quota-based 429 is introduced. The in-flight map can contain only admitted leaders.

**Deployment:** One image serves two app instances and migration. Only migration gets DDL credentials; only app-1 runs cleanup. Default ingress is localhost NGINX; baseline Compose preserves a one-app no-cache option. NGINX 1.28.0 uses least connections/shared zone/Docker DNS resolve with two-second validity. It overwrites forwarding headers; app forwarding interpretation stays disabled. App instance IDs are server-selected and logged, with no untrusted hostnames copied from requests.

**Alternatives:** Permanent local caches, TTL renewed by hits or delayed fills, cache-as-authority, synchronous distributed fill locks, unrestricted DB fallback, more runtime pools without connection budgeting, static upstream IP resolution, and assumed zero-error passive failover. These weaken freshness/resource bounds or require more operational proof. Stronger takedown consistency is available through strict mode.

**Consequences:** Cached disables can remain briefly visible under the original deadline; an already-resolved request can complete in either mode. Redis loss adds bounded SQL work, but database capacity is unchanged. Two apps can consume 16 runtime connections and four event transactions; local queues remain lossy and uneven. NGINX passive recovery can show transient errors; live-but-unready apps are not automatically removed by continuous polling. No single-host high availability, skew measurement or throughput gain is claimed. A graphical client, quotas, production credentials/TLS and durable messaging are outside P5.

**Evidence:** Real Redis/PostgreSQL tests cover shared mappings/stats, unchanged PXAT across hits, corrupt values, eviction/restart, strict mode, delayed old-read refill and failed invalidation. Race tests accelerate the app clocks by 58 seconds while retaining original SQL/Redis deadlines, rather than inventing a 60-second wall-clock result. Test controls address the actual Spring proxy target and use a stable private Redis port across engine restart. The full Compose smoke measures each replica/Redis outage and replaces one app at a new IP. [Redis command](https://redis.io/docs/latest/commands/set/) and [NGINX upstream](https://nginx.org/en/docs/http/ngx_http_upstream_module.html) document the used primitives.


## ADR-021: Local private measurement and honest load gates

**Status:** Implemented and tested in P6; final human acceptance pending.

**Decision:** Instrument only the explicit `measure` profile. Keep its whitelisted meter endpoint on an unpublished management port in the disposable fixture. Record an all-status GET timer per replica, excluding HEAD; compare maximum approximate per-replica p95 without merging separate status percentiles. Preserve client/proxy latency separately. Use constant-arrival k6 load, actual instance/cache/SQL counters and SQL event counts after bounded drain.

**Rationale:** Public curl headers do not establish Redis hits, SQL reduction or event throughput. Worst-status-group percentiles can exaggerate an all-request percentile, so the aggregate redirect timer is explicit. A shared laptop can pause; elapsed-window checks and an optional process-scoped idle-sleep inhibitor prevent treating a disturbed run as capacity evidence.

**Consequences:** Measurement overhead/resource limits and warm-state preparation are disclosed. Warm entries retain original absolute deadlines. Sampled gauges can miss spikes. Fault variants can preserve redirects while failing healthy event/latency targets; failures stay in the report. No HA, linear scaling, maximum sustainable throughput or durable queue claim follows.

**Validation:** Separate-port/default-absence, all-status GET count and HEAD exclusion checks; real load/metrics/SQL reconciliation; raw artifacts, invalid-window records and reruns retained.

## ADR-022: Patch managed dependency families and fail closed on advisory gaps

**Status:** Implemented in P6.

**Decision:** Use the full resolved Maven graph and OSV known advisories, including test dependencies. Apply the minimal fixed minor-line versions across Spring/Tomcat/Netty/Log4j/JDBC/Jackson families via BOM properties. Fail on any indexed finding, missing tree, unavailable query or unsupported pagination.

**Rationale:** The first scan found 19 package/advisory pairs; no unsupported exploitability exception is used to hide them. Coordinated module versions plus the full regression suite verify compatibility. Curl retains system certificate verification after the local Python CA setup failed.

**Consequences:** The final indexed query is clean; this is neither a native-container/build-plugin audit nor an exploitability proof. Future advisories can fail CI until reviewed/fixed. Exact before/after evidence is in [dependency review](performance/source-review.md).
