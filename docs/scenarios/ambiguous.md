# Scenario C: Ambiguous Analytics Requirements

Status: P4 implementation and local validation complete; human semantics/diff acceptance pending. Source checkpoint: `23f50223065b0b2b3da03174a7688e55fb30ff25`.

## Requirement interpretation and assumptions

The assignment asks for analytics without defining people vs requests, HEAD, bots, successful browser delivery, timezone, retention, loss or lag. The implementable default counts each active GET resolution attempt; bots, prefetch and retries can count. Exclude HEAD, missing, disabled, expired, unsupported methods and lookup failures. Capture UTC observation time at resolution, with microsecond storage and monotonic queue age. No IP, user-agent, destination is persisted in event rows.

Stats cover committed observations retained for 30 days. UTC date windows are half-open, default to seven calendar days including today, and span at most 30 days. Fill requested empty/future buckets, cap query end at `asOf`, and report unavailable history. `asOf` is not a completeness watermark. Loss and delay are explicit: `recordingMode=best-effort-async`, `eventuallyConsistent=true`. Product confirmation is pending; assistant implementation is not stakeholder acceptance.

## Alternatives and decisions

Unique visitors require identity/privacy decisions; server resolution cannot prove browser follow-through. Synchronous counters impose hot-row writes. Durable transport adds operations and a delivery/availability contract. Unbounded queues or caller-runs defeat the resource/latency bounds. A bounded lossy executor provides a defensible prototype default; ADR-015/019 document the trade-offs. Rejected options are design alternatives, not invented evidence of rejected AI patches.

## Decomposition and execution

P4.1 semantics → P4.2 additive V3 → P4.3 publisher and worker-owned transaction → P4.4 GET-only submission → P4.5 UTC aggregation/DTO → P4.6 bounded retention → P4.7 loss/failure/lag and coordinated shutdown.

`LinkService.resolveObserved` captures the timestamp and returns immutable resolution details after mapping SQL releases its connection. `LinkController` submits only successful GETs. `ClickEventPublisher` owns named workers, bounded queue, admission and monotonic age. `TransactionalEventRecorder` includes commit inside the observed failure boundary. `StatsService` derives totals from a single bucket query. `RetentionCleanup` independently deletes bounded event/key batches with current-state predicates. `AnalyticsShutdown` drains HTTP before closing admission and explicitly closes Hikari after the remaining-budget drain.

## Validation evidence

`AnalyticsTest` exercises saturation without caller-runs, exact queue age, no-retry write/unknown-commit failures, validation and forced drain. `AnalyticsIT` uses actual HTTP/PostgreSQL for paused-worker lag and eventual persistence, exclusions, failed lookup, validated stats, UTC boundaries and delayed observations, retention/cleanup bounds, concurrent key replacement, event permissions/FK/UUID, fresh-context persistence and real HTTP-first shutdown.

The hard-stop test launches a separate JVM with a test-only paused recorder. One GET returns while its worker is paused, a second returns while queued, and SQL contains zero events. The test forcibly kills that JVM and verifies zero committed observations with the mapping retained. No production pause hook was introduced. Insert failure is tested against PostgreSQL; uncertain commit classification uses a controlled transaction-manager failure and does not claim a real network-partition test.

Commands, measured results and source checkpoint are in [validation results](../validation-results.md); generated/edited output and rationale are in [AI log](../ai-execution-log.md). The isolated [P4 Compose check](../../scripts/phase4-smoke.py) upgrades saved P3/V2 and checks committed stats across app/database restart.

## Review and remaining limits

Human acceptance is pending. Assistant diff review checks loss semantics, grants, transaction boundaries, query retention, cleanup races and shutdown ordering; it does not substitute for engineer acceptance. Cross-replica behavior arrives in P5; throughput/p95 and dependency audit in P6. Shared-pool contention, lossy crash behavior, physical-cleanup backlog and framework lifecycle coupling remain explicit risks.
