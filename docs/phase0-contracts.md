# Phase 0: Scope, Contracts, and Acceptance

Status: Phase 0 artifacts authored; Phases 1–5 runtime implemented. This document preserves staged defaults and the original Phase 0 disposition. Human contract/product review is pending. Authorization to prepare these artifacts is not a claim that the human has approved every assumption. Sources: [implementation plan](implementation-plan.md), [assignment](problemStatement.md), [architecture](architecture.md), [edge cases](edge-case.md), and [OpenAPI contract](../openapi.yaml).

## P0.1 — Scope and requirement traceability

| ID | Origin | Included behavior / artifact | Acceptance and planned validation |
| --- | --- | --- | --- |
| REQ-01 | Assignment | Working core APIs, analytics, reliability | HTTP create → resolve → eventual stats → disable; lifecycle/failure tests, P2–P6 |
| REQ-02 | Assignment | Greenfield, brownfield, ambiguous scenarios | Each record has decomposition, execution, validation, review; checkpoints demonstrate real change |
| REQ-03 | Assignment | Maintainable code and API/schema definitions | Layered Java implementation, OpenAPI, named constraints, migration tests |
| REQ-04 | Assignment | Unit/integration tests, setup and engineering summary | Blocking verification, isolated setup replay, accurate evidence, P1–P7 |
| REQ-05 | Assignment | AI implementation/debugging/refactoring/tests/docs/review | Actual generated/edited/rejected dispositions with rationale, commands/results and review |
| REQ-06 | Assignment | Risks, trade-offs, assumptions and human oversight | Decision records and sign-offs distinguish intended behavior from evidence |
| USR-01 | User choice | Java/Spring Boot and PostgreSQL | Java 21 target; Boot patch pinned in P1; runtime/tests use PostgreSQL |
| USR-02 | User direction | NGINX, shared Redis, local Docker scaling | Two replicas, cross-instance cache behavior and measured routing/recovery, P5–P6 |
| DIR-01 | Authorized design update | Asynchronous analytics | Bounded admission, separate worker transaction, no waiting for analytics SQL |
| ASM-01 | User-selected scope | Public HTTP APIs | No credential setup for creation, metadata, disable, stats or redirects |
| ASM-02 | Proposed design default | Optional idempotency and random codes | Atomic keyed creation, fresh-transaction collision retries, 24-hour replay |
| ASM-03 | Proposed design default | Expiry, disabling and cached freshness | Additive V2; exact boundary; cached vs strict behavior disclosed |
| ASM-04 | Proposed design default | UTC retained-event analytics | Observed GETs, 30-day retention, eventual stats and loss disclosures |

Rate limiting is optional and excluded from the implementation baseline: no client quotas, shared limiter scripts, `429` contract, or Redis-dependent management admission. Redis is for mapping caching. Connection, queue, input-size, and fallback bounds protect resources without imposing per-client quotas.

Deferred: UI, custom aliases, destination edits, accounts/ownership, runtime LLM integration, durable event broker, aggregate counters, hosted deployment, multi-host orchestration, backup/failover implementation. Backup/restore and production availability remain explicit gaps, not properties established by local volumes.

## P0.2 — API and DTO contracts

[openapi.yaml](../openapi.yaml) defines the **planned final P4+ surface**, not a running server. Operation and property phase markers identify staged delivery. P2 creation accepts only `url`; expiry is introduced in P3 and stats in P4. Current implemented checkpoint: [openapi-phase3.yaml](openapi-phase3.yaml); the P2 snapshot remains archived. Update checkpoint contracts alongside those changes. Do not expose a fake stats endpoint in the P2 baseline.

| Operation | Phase | Auth | Success | Expected errors |
| --- | --- | --- | --- | --- |
| POST `/api/v1/links` | P2; expiry P3 | Public | `201` link creation result, including keyed replay | `400`, `409`, `413`, `415`, `503` |
| GET `/api/v1/links/{code}` | P2 | Public | `200` current metadata | `404`, `503` |
| DELETE `/api/v1/links/{code}` | P2 | Public | `204`, including repeated disable | `404`, `503` |
| GET `/api/v1/links/{code}/stats` | P4 | Public | `200` committed retained counts | `400`, `404`, `503` |
| GET `/r/{code}` | P2 | Public | `302`, Location and no-store | `404`, `410`, `503` |
| HEAD `/r/{code}` | P2 | Public | Same lifecycle status/headers; no body/event | `404`, `410`, `503`, no bodies |
| GET `/health/live` | P1 | Public | `200` minimal UP status | Process unreachable is not a JSON response |
| GET `/health/ready` | P1 | Public | `200` minimal UP status | `503` minimal DOWN status |

Application errors use `{ "error": { "code", "message", "requestId" } }` with safe messages. Unhandled application failures use `500` / `INTERNAL_ERROR`; a dependency failure uses `503` / `DEPENDENCY_UNAVAILABLE`. Gateway/parser-level failures need not use the application's JSON envelope. No SQL, stack trace, token or submitted URL is returned in errors.

Every app response carries server-generated `X-Request-Id`. Management, redirects and errors use `Cache-Control: no-store`. Unsupported redirect methods return `405` with `Allow: GET, HEAD`; HEAD error responses have no body. `X-App-Instance` is a P5 local diagnostic, not a required public API field. Cross-origin allowed origins are not configured.

### DTO definitions for implementation

The schema definitions are concrete DTO contracts; Java records are implemented after bootstrap rather than introducing an unbuildable source tree in Phase 0.

| DTO | Fields / Java mapping | Rules |
| --- | --- | --- |
| `CreateLinkRequest` | `String url`, P3 nullable `Instant expiresAt` | No unknown fields or duplicate JSON keys; constrained URL policy; new expiry must be future |
| `LinkResponse` | `String code`, `String shortUrl`, `String url`, `Instant createdAt`, nullable `Instant disabledAt`, P3 nullable `Instant expiresAt` | Destination immutable; internal UUID not exposed |
| `DailyRedirectCount` | `LocalDate date`, `long observedRedirects` | Nonnegative count; UTC bucket |
| `LinkStatsResponse` | Code, UTC `from`/`to`, `long observedRedirects`, ordered daily list, `Instant availableFrom`/`asOf`, retention/consistency flags | Total is sum of returned buckets; no lifetime/unique-person count |
| `ApiErrorResponse` / `ApiError` | Safe code/message/request ID | Stable enum in OpenAPI; no sensitive exception text |
| `HealthResponse` | Status `UP` or `DOWN` | No dependency internals |
| `RedirectEvent` (internal) | UUID event/link IDs, UTC observation time; safe correlation ID and monotonic enqueue tick in memory only | No servlet object, IP, full user agent, or destination copied to event storage |

### Idempotency contract

One optional `Idempotency-Key` header accepts 1–128 ASCII characters matching `[A-Za-z0-9._:-]+`. Empty, repeated, comma-combined, whitespace-containing, or over-limit headers return `400 / INVALID_IDEMPOTENCY_KEY`. No trimming. The key has a global namespace; SHA-256 uses the existing length-prefixed encoding to preserve stored-key compatibility. A new key's lifetime is `[createdAt, createdAt + 24 hours)`.

The fingerprint is SHA-256 over a canonical structured encoding of the validated ASCII destination and normalized expiry (or null). Preserve meaningful path/query/fragment content. Store a key digest and fingerprint, not raw keys. Match replays return the original `201` creation result, with original `disabledAt: null`; metadata GET returns current lifecycle state. Because destination/creation/expiry are immutable and mappings are retained, the original result can be reconstructed. A mismatch within lifetime gives `409`. Do not reapply a future-expiry rule to a matching replay.

At/after lifetime end, a key may create a new link even if stale rows remain. Conditionally remove/replace stale records transactionally; row locks/uniqueness arbitrate retries. Cleanup is not the replay clock. A losing candidate rolls back before reading the winner in a fresh transaction. No key means duplicates are possible after an uncertain response; no guarantee survives deleting durable state or restoring history from an older backup.

## P0.3 — Schema and migration boundaries

See [schema-plan.md](schema-plan.md) for columns, named constraints, transaction boundaries and upgrade acceptance. Do not create applied migration history in Phase 0.

| Planned migration | Implementation phase | Boundary |
| --- | --- | --- |
| `V1__core_links_and_idempotency.sql` | P1 | Links and key records; no expiry/event table |
| `V2__add_link_expiration.sql` | P3 | Nullable expiry; existing links remain non-expiring |
| `V3__redirect_events.sql` | P4 | Append-only observed events and query/cleanup indexes |

One migration runner applies pending changes before app readiness; replicas verify expected schema without DDL credentials. Record a P2 source/database checkpoint before the V2 upgrade. Never edit an applied migration as a brownfield shortcut.

## P0.4 — Analytics, cache, and contract-gap dispositions

The following defaults have been reviewed for internal consistency by the assistant and remain **pending human review**. They provide implementable acceptance conditions without claiming stakeholder confirmation. ADR-016 records their status.

| Gap / topic | Implementation default and rationale |
| --- | --- |
| GAP-01: parsing | Reject duplicate JSON keys and unknown fields with `400 / INVALID_REQUEST`. Only JSON creation bodies supported (`415 / UNSUPPORTED_MEDIA_TYPE` otherwise). Empty body is `400`. Body-size bounds and strict DTO/business validation apply to every caller. |
| GAP-02: URL policy | Reject surrounding whitespace and control characters. Require ASCII DNS host or canonical IP literal; internationalized hosts must be supplied as punycode for this prototype. Unicode path/query may be URI-serialized to ASCII without changing meaning. Enforce 2,048 code points before and 2,048 characters after serialization. Reject userinfo, explicit loopback/private/link-local literals, localhost/local-only names, and the configured public hostname case-insensitively regardless of scheme/port. Reject noncanonical numeric IP spellings; no DNS lookup/reputation guarantee. |
| GAP-03: keys | Use the format, global scope and half-open 24-hour replay window above; stale rows do not extend replay. Remove only records whose locked/current state is expired. |
| GAP-04: routing/protocol | Redirect codes are case-sensitive eight-character Base62. Malformed codes, extra path segments and trailing slash do not resolve (`404`); malformed HTTP/encoding may be rejected by ingress/container. Redirect OPTIONS and other unsupported methods use `405`, Allow GET/HEAD; management CORS preflight is not granted (`200` without Access-Control-Allow-Origin). Gateway `502`/`504` are transport failures, not app JSON guarantees. |
| GAP-05: time | Input timestamps must include an offset and at most six fractional digits; normalize to UTC, within years 0001–9999 after conversion. Reject leap seconds/out-of-range instants. Capture creation/event wall-clock instants at microsecond precision (truncate the injected clock reading, not an input expiry); capture creation clock once and compare expiry to that same instant; short lifetimes can expire before response delivery. Do not silently round input across lifecycle boundaries. Disabled takes error-code precedence if also expired; both status `410`. |
| GAP-06: queue age | Use monotonic elapsed time for queue wait. Drop only if wait is greater than five seconds; exactly five seconds is within the admission-age policy. UTC event time still comes from injected wall clock. No retry buffer/caller-runs. |
| GAP-07: ranges/retention | Both dates absent gives the seven-day default; exactly one supplied, duplicates, unknown stats parameters, `from >= to`, or window >30 days gives `400`. Fill all requested UTC date buckets, capping effective query end at `asOf`; future/unavailable buckets are zero with disclosures, not proof of zero activity. Query includes events at cutoff `asOf - 30 days`; cleanup removes strictly older rows. `availableFrom = max(createdAt, cutoff)`; `partialRetention` is true when requested start precedes it. |
| GAP-08: tracing/proxy | Generate a fresh UUID request ID; ignore inbound correlation IDs. NGINX overwrites untrusted forwarding metadata and apps trust only private configured ingress. Instance header uses configured logical ID without hostname/secrets, enabled for local demo. |

Observed analytics count GET attempts resolved as active under the selected cached/strict policy. HEAD, errors, missing/inactive/expired links do not submit events. Bots/prefetch/retries can count separately. A committed event does not prove response delivery or browser follow-through. No synchronous per-link counter is added.

Two workers and 1,000 queued tasks per replica are initial bounds. Queue admission failures and worker transaction failures preserve valid redirects. Capture event observation time before enqueue; count only committed retained events. Stats disclose best-effort async mode and eventual consistency. `asOf` is not an ingestion-completeness watermark. Queue overload, age drops and crashes can undercount; uncertain commit outcomes are not called definite losses.

Cached mode has a maximum 60-second freshness window anchored to the original primary statement time, capped by link expiry, plus measured clock-skew tolerance (initial proposed tolerance one second). Hits do not renew it. Disable commits PostgreSQL then best-effort invalidates Redis; delayed refill must retain the old deadline. Strict mode bypasses mapping cache, but cannot cancel an already-resolved request. Never serve beyond the deadline on dependency failure. Redis failure uses bounded DB fallback, not a management quota failure.

## P0.5 — Evidence artifacts and Phase 0 disposition

Created templates: [AI execution log](ai-execution-log.md), [greenfield](scenarios/greenfield.md), [brownfield](scenarios/brownfield.md), [ambiguous analytics](scenarios/ambiguous.md), [validation results](validation-results.md), and [engineering summary](engineering-summary.md).

| Task | Artifact status | Verification / review status |
| --- | --- | --- |
| P0.1 | Scope separated and optional work excluded | Assistant consistency review; human review pending |
| P0.2 | OpenAPI and DTO/status/header definitions authored | See recorded static contract checks; runtime tests not run |
| P0.3 | Named schema and migration plan authored | SQL not applied; database validation starts P1 |
| P0.4 | Implementable defaults and trade-offs recorded | Product assumptions not human-approved; no new approval gate imposed on reversible drafting |
| P0.5 | Evidence templates and actual Phase 0 log created | Later evidence remains explicitly not-run/unimplemented |

Phase 0 artifact preparation is complete: the documented static checks passed (P0-VAL-01 through P0-VAL-05). Human acceptance remains pending and must not be marked approved by the assistant. No P1 application code, credentials, public deployment, or database changes are included in this work.
