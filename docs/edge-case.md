# URL Shortener: Edge Cases and Failure Scenarios

## 1. Purpose and scope

This catalog translates [implementation-plan.md](implementation-plan.md) and [architecture.md](architecture.md) into corner scenarios, expected behavior, and validation methods. It covers the proposed Java/Spring Boot, PostgreSQL, Redis, NGINX, two-replica deployment and bounded asynchronous analytics.

This catalog defines intended coverage; implemented P1–P4 subsets and remaining cases are recorded in [validation results](validation-results.md). No claim is made that every catalog row has executed. This is a coverage catalog, not a claim that every possible input or failure combination has been enumerated. Cases originally marked as contract gaps now have drafted Phase 0 defaults in [phase0-contracts.md](phase0-contracts.md). Human review is pending; use those explicit defaults for implementation assertions rather than silently choosing framework behavior.

The implementation plan takes precedence for scope: rate limiting is optional. Client quotas, distributed limiter counters, limiter-specific `429` responses, and Redis-dependent management admission are not required here. Resource protections—bounded requests, connection pools, cache fallback concurrency, and analytics queues—remain in scope. UI, aliases, multi-user ownership, durable messaging, and production failover remain deferred.

### How to use this catalog

- **C — Critical:** integrity, lifecycle, security, or fundamental failure behavior; relevant cases must pass before the affected phase is accepted.
- **I — Important:** boundary, compatibility, and operational coverage; execute in the relevant phase or record why it remains unverified.
- **X — Extended:** deeper stress or production-evolution coverage; useful evidence, not an automatic expansion of the prototype scope.
- **U:** deterministic unit tests; **H:** HTTP/controller tests; **D:** real PostgreSQL integration; **R:** real Redis integration; **E:** Compose/end-to-end; **L:** measured load/fault experiments.

The phase column identifies the first implementation phase owning the case. Re-run relevant regressions in P6 and reconcile evidence in P7. Priorities describe this catalog's recommended test order, not additional requirements stated by the assignment.

## 2. Invariants that apply across scenarios

1. A public code identifies one immutable destination; ordinary disable, expiry, cleanup, and restart do not reassign it.
2. PostgreSQL unique constraints and transactions arbitrate races, not an application pre-check.
3. Link creation and its idempotency record either both commit or both roll back.
4. GET and HEAD follow the same resolution policy, but HEAD never submits analytics.
5. Missing, inactive, expired, and failed resolutions do not submit an event.
6. A valid redirect does not wait for analytics SQL; submission or worker failure does not turn it into a server error.
7. Counts describe committed, retained observed events, not people, successful navigation, or complete delivery.
8. Cache hits never extend the original freshness deadline. Redis is not the authoritative link store.
9. Cached disabling can lag within the documented freshness window; strict resolution checks the primary. Neither cancels a request already resolved.
10. Committed database history survives an ordinary retained-volume restart; pending executor tasks need not survive.
11. Queues, workers, database pools, and fallback work remain bounded under failure.
12. Credentials, destination bodies/query strings, SQL internals, and personal data are not leaked through errors, logs, metrics, or AI evidence.

## 3. URL input and request parsing

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| URL-01 | P2 / C | Missing, null, empty, or whitespace-only `url` | Safe `400`; no link/key persistence | U/H/D |
| URL-02 | P2 / C | Relative path, missing host, or malformed authority | Reject with `400`; do not infer a default scheme/host | U/H |
| URL-03 | P2 / C | `javascript:`, `data:`, `file:`, FTP, or another unsupported scheme | Only absolute HTTP/HTTPS accepted; no execution or fetch | U/H |
| URL-04 | P2 / C | Embedded credentials such as `https://user:pass@example.com` | Reject userinfo; redact the original value in errors/logs | U/H |
| URL-05 | P2 / C | Literal or encoded control characters, CR/LF, null bytes | Reject unsafe forms before constructing `Location`; no header injection | U/H |
| URL-06 | P2 / C | Explicit loopback/private IPv4 or IPv6 literal and local-only hostname | Reject according to documented URL policy; valid public IPv6 parsing must not be confused with userinfo | U/H |
| URL-07 | P2 / C | Destination points back to this service's redirect host | Reject self-redirect under the configured policy; do not trust inbound Host for that decision | U/H |
| URL-08 | P2 / I | URL at 2,048 characters, or just over it | Boundary accepted if otherwise valid; over-limit input rejected. Resolve character/ASCII representation rules in GAP-02 | U/H |
| URL-09 | P2 / I | Unicode host/path, punycode, percent escapes, upper-case scheme/host | Validate host handling and preserve destination meaning; exact normalization policy in GAP-02 | U/H/D |
| URL-10 | P2 / C | Repeated/ordered query parameters, fragments, escaped delimiters | Preserve meaningful content; do not strip trackers, reorder parameters, or decode into a different destination | U/H/D |
| URL-11 | P2 / I | Leading/trailing whitespace around an otherwise valid URL | Consistent accept/trim/reject policy needed; see GAP-02 | U/H |
| URL-12 | P2 / C | Destination is unreachable or its DNS changes after creation | No outbound fetch at creation or redirect; return the stored accepted destination. No later DNS/reputation guarantee | H/E |
| HTTP-01 | P2 / C | Malformed JSON, wrong field type, unexpected field | Safe `400`; no side effects; reject unknown fields | H/D |
| HTTP-02 | P2 / I | Duplicate JSON keys, unsupported media type, empty body | Deterministic contract needed; see GAP-01, rather than relying on inconsistent framework defaults | H |
| HTTP-03 | P2 / C | Request body at/above the proposed 16 KiB byte limit, including chunked input | Reject oversized input with `413`; align app/NGINX behavior; no partial creation | H/E/D |
| HTTP-04 | P2 / I | Encoded slash, malformed path encoding, extra path segments, unusual trailing slash | No accidental code match or redirect to an unrelated route; specify routing/error details in GAP-04 | H/E |

No network lookups are required merely to validate destination URLs. Private-address filtering is a baseline policy, not proof against all alternative address encodings or future DNS changes; record parser limitations rather than claim comprehensive SSRF protection for a service that does not fetch destinations.

## 4. Codes, transactions, and creation concurrency

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| CRT-01 | P2 / C | Generator produces boundary alphabet characters | Exactly eight Base62 characters; preserve case; use injected generator in tests | U/D |
| CRT-02 | P2 / C | Generated code already exists | Named unique constraint rejects it; retry within the five-attempt budget in a fresh transaction | U/D |
| CRT-03 | P2 / C | Two replicas generate the same code concurrently | One insert wins; other retries; no overwrite or duplicate public identifier | D/E |
| CRT-04 | P2 / C | Every allowed collision attempt fails | Controlled `503` and collision metric; no infinite retry or partial record | U/D/H |
| CRT-05 | P2 / C | FK/check constraint, permissions, or connection failure is mistaken for code collision | Only the intended code uniqueness violation triggers code regeneration | D |
| CRT-06 | P2 / C | Retry after PostgreSQL transaction becomes aborted | Roll back first; no reuse of a failed transaction/connection state | D |
| CRT-07 | P2 / I | Two requests submit the same destination without an idempotency key | Separate links are allowed; destination deduplication is not promised | H/D |
| CRT-08 | P2 / C | Generated candidate equals an expired/disabled code | Registry entry still blocks reuse | D |
| CRT-09 | P2 / C | Crash between link insert and idempotency insert | Neither partial transaction is visible after recovery | D/E |
| CRT-10 | P2 / C | Creation commits but response is lost | Commit remains; keyed retry can recover it. Unkeyed retry may create another link | H/D/E |
| CRT-11 | P2 / I | Client disconnects while a transaction is in progress | No assumed automatic rollback; inspect committed outcome and apply retry contract | H/D/E |
| CRT-12 | P2 / C | Malicious Host/forwarded-host header during creation | Returned short URL uses validated `PUBLIC_BASE_URL`, not client headers | H/E |

## 5. Idempotency and retries

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| IDE-01 | P2 / C | Same key and normalized request, sequential replay | Return original creation result; exactly one committed link/key pair | H/D |
| IDE-02 | P2 / C | Same key/request concurrently on both replicas | Unique key arbitrates; loser rolls back candidate and reads winner in a fresh transaction | D/E |
| IDE-03 | P2 / C | Same key but different URL or expiry | `409`; do not modify original link or leave a losing candidate | H/D |
| IDE-04 | P2 / I | Same destination with different keys | Independent creations allowed | H/D |
| IDE-05 | P3 / C | Matching replay after original link expires | Replay original result before applying new-creation future-expiry rule; do not recreate/reactivate | H/D |
| IDE-06 | P2 / C | Matching replay after disabling | Original result is replayed; link remains disabled | H/D |
| IDE-07 | P2 / I | Key at 128 characters, over limit, empty, whitespace-only, duplicated header | Maximum length enforced; format/empty/multiple-header policy in GAP-03 | H |
| IDE-08 | P4 / C | Replay just before, at, or after the 24-hour boundary | Enforce a documented retention boundary independently of delayed cleanup; see GAP-03 | D/H |
| IDE-09 | P2 / C | Network failure makes creation commit outcome uncertain | Do not assert rollback merely from timeout; keyed retry resolves within retention guarantee | D/E |
| IDE-10 | P2 / I | Equivalent expiry instants expressed with different offsets | Normalize to UTC for fingerprint; replay equivalent request if URL normalization also matches | U/H/D |
| IDE-11 | P4 / C | Cleanup races with retry or key recreation | Maintain atomic creation; do not delete a newly created key record through stale cleanup selection | D |
| IDE-12 | P2 / C | Raw key enters logs, SQL errors, or metrics | Persist documented digest/fingerprint; redact secrets; no high-cardinality raw key label | H/D |

## 6. Redirects, lifecycle, and time boundaries

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| RED-01 | P2 / C | Invalid-length/non-Base62 public code | `404` before lookup; no event | U/H |
| RED-02 | P2 / C | Well-formed but unknown code | `404`; no analytics; no initial negative caching | H/D/R |
| RED-03 | P2 / C | Active GET vs HEAD | Both `302` with `Location` and `no-store`; HEAD has no body/event | H/D |
| RED-04 | P2 / C | HEAD for missing or inactive link | Same lifecycle status as GET, no body and no event | H/D |
| RED-05 | P2 / I | POST/PUT/DELETE on public redirect route | Documented `405` and `Allow`; no resolution/event side effects. OPTIONS details in GAP-04 | H/E |
| RED-06 | P2 / C | Disable unknown link or disable same existing link twice | Unknown `404`; repeated existing disable `204`; record retained | H/D |
| RED-07 | P2 / C | Disable commits before uncached/strict resolution reads | Resolution observes disabled state and returns `410` | D/H |
| RED-08 | P5 / C | Disable commits after a request already resolved | In-flight redirect may complete; cached mode can also lag to original deadline | D/R/E |
| RED-09 | P3 / C | No expiry, omitted expiry, or explicit null | Link remains non-expiring; existing V1 data remains usable | U/H/D |
| RED-10 | P3 / C | New expiry is past, exactly now, malformed, or invalid calendar date | Reject invalid new creation; no insert. Timestamp representation details in GAP-05 | U/H/D |
| RED-11 | P3 / C | Resolution just before, exactly at, or after expiry | Before `302`; at/after `410`; both GET/HEAD honor boundary; no expired event | U/H/D/R |
| RED-12 | P3 / I | Expiry becomes past between validation and insert/response | Do not promise a minimum usable lifetime; handle schema/clock precision consistently, see GAP-05 | D/H |
| RED-13 | P3 / C | Expired link also disabled | Both produce `410` and no event; any distinct error-code precedence needs GAP-05 decision | H/D |
| RED-14 | P4 / I | Browser prefetch, bots, or repeated client/proxy GET retries | Each successful observed GET can count; no unique-user or exactly-once visit claim | H/D/E |
| RED-15 | P2 / C | Browser or NGINX attempts to cache redirect response | `no-store` on redirects; NGINX response cache disabled; lifecycle goes through app | H/E |
| RED-16 | P3 / I | JVM timezone differs; fractional timestamp precision changes on DB round trip | Preserve UTC instant semantics at supported precision; see GAP-05 for alignment | U/D |

## 7. Redis cache freshness, races, and fallback

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| CAC-01 | P5 / C | Warm valid cache hit | No mapping SELECT; still validate embedded freshness/expiry; submit GET analytics | R/D/E |
| CAC-02 | P5 / C | Cold miss | Read primary, validate lifecycle, fill active typed mapping with absolute deadline | R/D |
| CAC-03 | P5 / C | Many hot-key misses across replicas | Duplicate reads may occur; per-replica coalescing/fallback remain bounded and release permits on errors | R/D/L |
| CAC-04 | P5 / C | Repeated cache hits | Deadline is never extended; original read time remains the anchor | U/R |
| CAC-05 | P5 / C | Cache freshness expires while actual link remains active | Treat entry as miss; not `410` solely because cache TTL ended | R/D/H |
| CAC-06 | P5 / C | Actual link expiry precedes 60-second freshness | Absolute cache deadline is capped by expiry; app checks exact lifecycle boundary too | U/R/H |
| CAC-07 | P5 / C | Old reader pauses, disable commits/deletes, reader then fills | Old absolute deadline prevents a fresh stale window; eventual convergence is demonstrated | D/R |
| CAC-08 | P5 / C | Redis invalidation fails after DB disable commits | Disable stays committed; meter invalidation failure; allow only documented cached staleness | R/D/H |
| CAC-09 | P5 / C | DB disable rolls back | Do not publish successful disable or post-commit invalidation; original link remains active | D/R |
| CAC-10 | P5 / C | Fill reaches Redis after its deadline has passed | Skip stale fill; never revive deadline by using a fresh relative TTL | U/R |
| CAC-11 | P5 / C | Truncated JSON, missing fields, unsupported schema version, inconsistent lifecycle | Treat invalid cache value as miss; never trust malformed destination/deadline | R/H |
| CAC-12 | P5 / C | Cached ID/code disagree or cached record is disabled | Reject inconsistent value and use primary; do not submit event for wrong link | R/D |
| CAC-13 | P5 / C | Redis read fails, times out, or breaker is open | Bounded DB fallback; management is not rejected merely because Redis is down | R/D/E |
| CAC-14 | P5 / C | Redis SET fails after a successful primary lookup | Valid redirect still succeeds; meter fill failure; PostgreSQL mapping unchanged | R/D/H |
| CAC-15 | P5 / C | Redis evicts keys or restarts | Rebuild mappings from primary; no loss of committed links/events | R/D/E |
| CAC-16 | P5 / C | Redis and PostgreSQL fail together | Miss/strict resolution fails with controlled dependency error; no stale-beyond-deadline serving | E |
| CAC-17 | P5 / I | Fresh cached entry exists during DB outage | Already-admitted cached request may redirect until deadline; DB readiness still fails, with no cached-only availability promise | E |
| CAC-18 | P5 / C | Strict consistency enabled despite warm cache | Read primary every time; cached state cannot override disabled/expired primary state | R/D/H |
| CAC-19 | P5 / I | Clock skew among app, DB, Redis or abrupt wall-clock change | Measure freshness error; enforce/monitor proposed tolerance; no unsupported absolute consistency claim | U/R/E |
| CAC-20 | P5 / C | No-op cache adapter or caching disabled | Baseline database resolution remains functional; no hidden Redis requirement | H/D |

Redis is disposable but still a trusted internal dependency. Corruption handling is not a cryptographic integrity guarantee against a compromised Redis service. Do not populate lifecycle mappings from a lagging read replica.

## 8. Async analytics delivery and backpressure

P4 evidence covers paused HTTP/SQL separation, reduced-capacity deterministic saturation, exact monotonic age, write/unknown-commit classification, GET exclusions, UTC observation, graceful and hard shutdown, event uniqueness and retention races. Saturation tests use one worker/one queued task to force the same configurable executor boundary; the default 2/1,000 deployment has not been load-tested. Sustained-load, actual network lost-ack and cross-replica cases remain unverified. See [ambiguity scenario](scenarios/ambiguous.md).

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| ASY-01 | P4 / C | Recorder paused before SQL while valid GET completes | HTTP does not wait for event SQL; eventually recorded after worker resumes | H/D |
| ASY-02 | P4 / C | Both workers busy and queue reaches 1,000 tasks | Next admission throws/rejects; publisher meters drop and still redirects | U/H/D |
| ASY-03 | P4 / C | Sustained arrivals exceed recorder throughput | Memory/concurrency remain bounded; observable drops/lag; no caller-runs or unbounded retry queue | L |
| ASY-04 | P4 / C | Task waits beyond five seconds or exactly at boundary | Drop past-limit event before SQL; fix inclusive boundary/age clock in GAP-06 | U/D |
| ASY-05 | P4 / C | Executor shutting down rejects submission | Catch rejection at publisher; otherwise-valid resolved GET still redirects | U/H |
| ASY-06 | P4 / C | Insert statement, lock wait, or commit fails | Worker transaction rolls back where known; observe complete transaction failure; do not affect HTTP response | D/H |
| ASY-07 | P4 / C | Commit acknowledgement lost | Record unknown outcome; no blind retry or claim that event definitely vanished | D/E |
| ASY-08 | P4 / C | Request transaction/thread ends before worker starts | Immutable payload remains usable; worker obtains its own transaction/connection | D/H |
| ASY-09 | P4 / C | `@Async` alternative is self-invoked or wrong executor is selected | Integration test detects HTTP/SQL coupling; use the named bounded executor and separate bean | H/D |
| ASY-10 | P4 / C | HEAD, unknown, expired, inactive, or failed lookup | No task admitted; zero relevant event-write attempts | H/D |
| ASY-11 | P4 / C | UTC midnight crosses between resolution and insert | Bucket uses resolution `occurredAt`, not worker execution time | U/D |
| ASY-12 | P4 / C | Disable/expiry occurs after a valid resolution but before event commit | Event may still persist for that observed attempt; do not reclassify it from later lifecycle state | D |
| ASY-13 | P4 / C | Immediate stats before queued event commits | May show zero; eventual-consistency metadata remains present; poll with deadline | H/D |
| ASY-14 | P4 / C | App crashes with pending and already committed events | Pending/uncommitted work may be lost; committed events remain; final drop metric may be absent | E |
| ASY-15 | P4 / C | Graceful shutdown with requests and workers active | Drain requests, close analytics admission, drain within shared budget, close datasource last | E |
| ASY-16 | P4 / I | Drain deadline reached or worker stuck on network I/O | Best effort cancellation and observable discarded tasks where possible; no forced complete-delivery claim | D/E |
| ASY-17 | P4 / C | Same internal UUID accidentally inserted twice | PK prevents duplicate persisted event; classify correctly, not as code-generation collision | D |
| ASY-18 | P4 / I | Client retries GET with a new event UUID | Separate observed attempt may count again; internal deduplication does not deduplicate visits | H/D |
| ASY-19 | P4 / C | MDC/security context reused on worker threads | Copy only safe correlation context if needed and clear it; no credentials, servlet objects, or previous-task leakage | U/H |
| ASY-20 | P5 / I | One replica queue saturated, other idle, or replica count increases | Queues are local and cannot rebalance accepted tasks; aggregate DB demand grows; record uneven load | E/L |

Initial settings are two workers and 1,000 pending tasks per replica, not a throughput guarantee. The eight-connection pool is shared with requests and cleanup; two background workers do not reserve six connections for HTTP. Every successful persisted GET still consumes PostgreSQL write capacity.

## 9. Stats queries, UTC ranges, and retention

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| STA-01 | P4 / C | Existing link with no committed events | Zero total and zero-filled requested buckets; disclose best-effort/eventual recording | H/D |
| STA-02 | P4 / C | Stats for unknown, disabled, or expired link | Unknown `404`; existing retained link can expose historical stats with authorization | H/D |
| STA-03 | P4 / C | No dates supplied | Last seven UTC calendar days, today included, tomorrow exclusive | U/H |
| STA-04 | P4 / I | One date missing, equal dates, duplicate parameters | Exact semantics unresolved; GAP-07 before implementation | U/H |
| STA-05 | P4 / C | Reversed range, malformed date, or window over 30 days | Safe `400`; exactly 30-day valid window accepted | U/H |
| STA-06 | P4 / C | Event exactly at `from` or `to` | Include at `from`; exclude at `to`; use normalized UTC instants | D |
| STA-07 | P4 / I | Leap day, month/year boundary, or DST transition | Valid UTC calendar arithmetic; local timezone/DST does not change buckets | U/D |
| STA-08 | P4 / C | Events commit while aggregation runs | Total equals returned bucket sum from one query result; no completeness guarantee | D |
| STA-09 | P4 / C | Requested interval starts before creation or retention availability | Clamp to available data; disclose `availableFrom` and `partialRetention` | H/D |
| STA-10 | P4 / I | Entire interval predates available history or lies in future | No invented counts; cap effective end at `asOf`; define partial-range representation in GAP-07 | H/D |
| STA-11 | P4 / C | Cleanup delayed/down but rows exceed 30-day retention | Queries still exclude out-of-retention events | D |
| STA-12 | P4 / C | Event exactly at retention cutoff | Align cleanup/query inclusivity; see GAP-07 to avoid disagreement | D |
| STA-13 | P4 / C | Cleanup overlaps event insert/stats query | Bounded transactions; correct retained query semantics; no full-table lock dependency | D/L |
| STA-14 | P5 / C | app-1 cleanup owner down, app-2 running | Cleanup pauses; query retention still enforced; owner catches up after return | E |
| STA-15 | P4 / I | Large count or many events for a hot link | Preserve PostgreSQL count precision and DTO integer range; never overflow silently | U/D/L |
| STA-16 | P4 / C | Stats DB unavailable | Controlled dependency error, not fabricated zero results | H/D/E |

`asOf` describes the query's observation snapshot; older events can commit afterward and change a later result. No lifetime total, guaranteed ingestion watermark, unique-user count, country/browser dimension, or exact historical completeness is promised.

## 10. PostgreSQL, migrations, and persistent recovery

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| DB-01 | P1 / C | Empty database vs already migrated database | Apply full chain once; subsequent startup validates without destructive replay | D/E |
| DB-02 | P3 / C | V1 seeded rows upgraded with V2 | Old links/IDs unchanged, nullable expiry preserves behavior | D |
| DB-03 | P4 / C | V2 upgraded with V3 event storage | Existing link/key data preserved; new event FK/indexes work | D |
| DB-04 | P1 / C | Applied migration checksum changed or schema version incompatible | Migration/replica compatibility gate fails clearly; do not auto-repair history | D/E |
| DB-05 | P1 / C | Migration runner fails or is interrupted | No app declares ready on incomplete schema; inspect history/state before retry | D/E |
| DB-06 | P5 / C | Both app replicas accidentally attempt schema changes | Runtime credentials cannot perform migration DDL; one-shot job owns migration | D/E |
| DB-07 | P1 / C | Runtime role lacks required DML or has excess DDL privileges | Detect missing privileges; verify intended separation and document grants | D/E |
| DB-08 | P6 / C | Pool exhausted, lock blocked, query slow, or network blackholed | Bounded waits/errors; rollback and return resources; timeouts are not a hard response guarantee | D/E/L |
| DB-09 | P6 / C | Event writer saturates shared pool during cold-cache load | Measure request contention; bounded workers/fallback prevent unbounded accumulation | D/L |
| DB-10 | P6 / C | Database full/read-only or connection lost on write | Creation/disable fails safely; already-resolved redirects tolerate analytics failure | D/E |
| DB-11 | P6 / C | App restart, DB restart, or normal Compose stop/start | Committed data persists with volume retained; queues excluded | E |
| DB-12 | P6 / I | Volume removed, wrong volume attached, old backup restored | Outside restart guarantee; detect/document changed history and code-reuse risk; never run destructive reset against user data | Isolated E |
| DB-13 | P1 / C | H2 substituted or integration tests point to development DB | Reject verification setup; use isolated PostgreSQL with real migrations | D/CI review |
| DB-14 | P6 / X | Deadlock or overlapping administrative work | Rollback safely, release connections, no unlimited generic retry | D/L |

## 11. Public API access, information exposure, and protocol handling

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| SEC-01 | P2 / C | Management/stats request without credentials | Public access; valid requests succeed, invalid requests retain documented validation/lifecycle errors | H |
| SEC-04 | P2 / I | Browser cross-origin management request/preflight | No unconfigured allowed browser origin; reconcile OPTIONS handling in GAP-04 | H/E |
| SEC-05 | P2 / C | URL/key includes quotes or SQL-shaped input | Parameterized SQL; input never executed; valid destinations retain meaning | H/D |
| SEC-06 | P2 / C | Internal exception includes SQL, DB URL, or destination | Safe error body and sanitized logs; request ID available | H/D |
| SEC-07 | P5 / C | Client spoofs forwarded host/IP/proto or request correlation header | Apply documented proxy trust boundary and bounded/safe correlation policy; GAP-08 | H/E |
| SEC-08 | P5 / C | Direct app/DB/Redis port access from host | No exposure by default; optional developer override explicitly documented | E |
| SEC-09 | P6 / C | Health endpoint queried during dependency failure | Minimal status only; no credentials/connection details; liveness independent of DB, readiness includes DB/schema | H/E |
| SEC-10 | P6 / I | Metrics use raw code/event/request IDs or destination labels | Bounded cardinality; safe route/instance labels; correlation IDs confined to appropriate logs | Review/L |
| SEC-11 | P7 / C | AI prompt/evidence contains credentials or instructions embedded in external data | Redact secrets; treat external content as data; record actual reviewed output, not fabricated approvals | Evidence review |


## 12. NGINX, replicas, Docker, and operational load

| ID | Phase / priority | Corner scenario | Expected behavior | Validation |
| --- | --- | --- | --- | --- |
| OPS-01 | P1 / C | Docker unavailable, required settings missing, invalid executor/pool limits | Clear prerequisite/startup failure; no false passing integration evidence | E/CI |
| OPS-02 | P5 / C | PostgreSQL healthy but migration/app not ready | Do not expose successful startup prematurely; schema completion gates replicas and ingress startup | E |
| OPS-03 | P5 / C | One app fails during requests | Passive upstream failure handling may cause transient errors; measure recovery, do not claim instantaneous failover | E/L |
| OPS-04 | P5 / C | Both apps unavailable or NGINX itself stops | Failure is visible; upstream gateway errors may differ from app `503`; exact response contract in GAP-04 | E |
| OPS-05 | P5 / C | Container replaced and service IP changes | Configured DNS resolution/reload reaches replacement; no permanent stale upstream address | E |
| OPS-06 | P5 / C | Proxy retries creation after upstream may have received POST | No automatic unsafe replay; client idempotency handles explicit retries | E/D |
| OPS-07 | P5 / I | Proxy retries GET or client disconnects during redirect | Duplicate observed events possible; response failure does not prove no event commit | E/D |
| OPS-08 | P5 / C | Two replicas use different public base URL, secret, schema, or cache mode | Detect configuration mismatch in startup/demo checks; do not claim consistent behavior | E |
| OPS-09 | P5 / I | `X-App-Instance` spoofed or missing | Header generated by configured app identity; verify both instances without requiring strict request alternation | E |
| OPS-10 | P6 / C | Warm-cache latency passes but queues keep growing/dropping | Load run is not healthy success; report persisted throughput, drops and lag | L |
| OPS-11 | P6 / C | Cold-cache, hot-key, many unique keys, Redis recovery | Bounded fallback, cache population and connection use; record overload rather than hide errors | L |
| OPS-12 | P6 / I | More replicas without more PostgreSQL capacity | Recalculate total pools/workers; do not infer linear scaling from replica count | L/config review |
| OPS-13 | P6 / I | CPU throttling, heap/GC pressure, host port conflict | Explicit setup/resource errors and measured impact; no unsupported capacity promise | E/L |
| OPS-14 | P6 / C | Cleanup scheduling enabled on both replicas accidentally | Configuration test detects duplicate ownership; local plan designates app-1 only | E |
| OPS-15 | P7 / C | Clean-checkout setup differs from original machine | Document pinned dependencies/runtime and reproduce actual commands/results | Isolated E |
| OPS-16 | P7 / C | Documentation presents proposals as implemented behavior | Correct status and cite actual results; disclose pending scenarios/failed load targets | Evidence review |

The local host, ingress, Redis, and PostgreSQL remain single failure points. Redis outage alone does not deny management access in the selected scope; DB availability and bounded work still determine success.

## 13. Contract gaps to resolve without expanding scope

These are ambiguities found while deriving cases, not new required features. Drafted dispositions for GAP-01 through GAP-08 are now recorded in [Phase 0 contracts](phase0-contracts.md) and ADR-016; human review is pending. This table preserves the original questions and affected cases. Assertions should follow the explicit defaults; genuinely unresolved changes remain pending-decision.

| Gap | Decision needed | Related cases |
| --- | --- | --- |
| GAP-01 | Duplicate JSON/header handling, unsupported media type status, empty-body errors, safe body/input-validation precedence | HTTP-02, SEC-01/02 |
| GAP-02 | Trim/reject surrounding whitespace; Unicode host handling; length measured before/after ASCII normalization; self-host matching across scheme/port/case and unsupported alternative IP encodings | URL-06/07/08/09/11 |
| GAP-03 | Empty/multiple idempotency headers; exact 24-hour boundary and behavior when stale key rows remain; fingerprint normalization and safe stale-key replacement | IDE-07/08/10/11 |
| GAP-04 | Routing normalization, trailing slash, OPTIONS/CORS precedence, and NGINX vs app error bodies/statuses | HTTP-04, RED-05, SEC-04, OPS-04 |
| GAP-05 | Supported timestamp precision/range, UTC conversion rounding, expiry validation-to-insert race, and error-code precedence for disabled+expired links | RED-10/12/13/16 |
| GAP-06 | Exactly-five-second queue-age boundary and elapsed-time source resilient to wall-clock adjustments | ASY-04, CAC-19 |
| GAP-07 | Equal/partially specified date ranges, duplicate query parameters, future/unavailable interval representation, exact query/cleanup retention cutoff | STA-04/10/12 |
| GAP-08 | Incoming request ID validation/regeneration, trusted forwarding header handling, and which diagnostic headers appear in local vs hosted configurations | SEC-07, OPS-09 |

Phase 0 reconciled migration staging across the documents: V1 links/idempotency, V2 expiry, V3 events. Actual upgrade evidence still belongs to the later phases.

## 14. Deterministic validation recipes for the hardest races

### A. Generated-code collision at insertion

Give two independent requests the same first generated code. Coordinate inserts against real PostgreSQL, allow one to commit, and force the other to encounter the unique constraint. Assert fresh-transaction retry, unique final codes, no overwritten destination, and no orphan idempotency candidate. An `exists` mock cannot prove this behavior.

### B. Idempotency race across replicas

Submit matching key/request pairs through each replica concurrently. Assert one committed link/key pair and matching creation results. Repeat with different fingerprints: one wins, the other receives `409`, and its candidate rolls back. Repeat after an uncertain response without inventing commit certainty.

### C. Delayed cache refill after disable

Start a primary read of an active link and capture its statement-start timestamp. Pause before cache fill. Commit disable, then perform best-effort invalidation. Resume the old fill. Cached redirects may continue only to that read's original deadline (with documented skew tolerance), never a newly started 60-second window. Strict resolution must observe the disabled primary state. Repeat with invalidation failure and with fill delayed beyond its deadline.

### D. Analytics saturation without blocking HTTP

Hold both workers with latches, fill the bounded queue, and issue one more valid GET. Assert `302` without worker completion, one observed rejection reason, bounded queue size, and no caller-runs SQL. Release workers and use bounded polling for committed events. Run age-expiry separately so queue aging does not invalidate count assertions unexpectedly.

### E. Shutdown and abrupt loss

Create committed events and separately queue known pending work. In an isolated test, perform graceful termination and verify HTTP→analytics→datasource shutdown order within the shared budget. Repeat with abrupt termination: committed history persists and pending work can disappear. Do not expect the terminated process to emit a final loss counter.

### F. Boundary-consistent stats

Seed events around UTC midnight, `from`, `to`, and the agreed retention cutoff. Delay another event's insert past midnight while keeping its original observation time. Query concurrently with commit and cleanup. Check bucket membership, total-equals-bucket-sum, partial-history metadata, and eventual-consistency disclosure; use committed snapshots rather than assuming all requests are already ingested.

## 15. Phase mapping, evidence, and completion

| Phase | Primary case families | Evidence focus |
| --- | --- | --- |
| P0 | GAP-01 through GAP-08 | Assumptions and exact contracts, optional scope kept optional |
| P1 | DB-01/04/05/07/13, OPS-01 | Real database/migrations, prerequisites, isolation and startup |
| P2 | URL, HTTP, CRT, IDE baseline, RED baseline, SEC | Greenfield API integrity/security and baseline checkpoint |
| P3 | RED expiry, IDE-05/10, DB-02 | Brownfield impact map, additive migration and boundary regression |
| P4 | ASY, STA, IDE retention, DB-03 | Ambiguous semantics, bounded delivery, eventual stats and loss |
| P5 | CAC, cross-replica CRT/IDE, OPS topology | Shared-cache correctness, routing, consistency and fallback |
| P6 | Cross-family fault/load cases | Timeouts, saturation, shutdown, recovery and measured throughput |
| P7 | OPS-15/16 and outstanding records | Reproducible setup, honest completion status and document agreement |

For each executed case, record the ID, source revision, environment/configuration, setup/input, expected result, observed result, test/command reference, relevant sanitized evidence, and engineer review. Recommended statuses: `not-run`, `pending-decision`, `pass`, `fail`, or `deferred-with-rationale`. Store actual results in `docs/validation-results.md` when created; keep this catalog as the behavioral reference.

Do not add a coverage percentage in place of behavior evidence. Use injected clocks/generators, barriers/latches, bounded eventual assertions, production migrations, real Redis/PostgreSQL, and isolated Compose fault runs as appropriate. Never drop an existing developer database/volume to exercise recovery. The initial catalog generation executed no application tests; subsequent phase evidence is recorded separately.

**Classification: Schwab Internal**


### P5 executed coverage

[CacheIT](../src/test/java/com/example/urlshortener/integration/CacheIT.java), [resolver tests](../src/test/java/com/example/urlshortener/cache/RedirectResolverTest.java) and [topology smoke](../scripts/phase5-smoke.py) exercise cache deadlines/non-renewal, delayed refill, failed invalidation, corruption, strict/no-op paths, coalescing/saturation, Redis restart/outage and cross-replica routing/replacement. [Observed results](validation-results.md) record 147 passing tests and the transient passive-failover 504. Catalog entries involving sustained load, actual clock skew, both-dependency outage combinations and final operational acceptance remain P6/P7 work; this is not complete catalog coverage.


### P6 executed coverage

[ReliabilityIT](../src/test/java/com/example/urlshortener/integration/ReliabilityIT.java) adds real pool exhaustion, held row lock, actual query cancellation, paused database networking versus fresh Redis, cleanup-owner shutdown/startup and private measurement-port/all-status GET timer checks. Existing analytics tests continue to verify queue admission/age, failure outcomes, actual HTTP-first drain and real hard-killed pending loss. [P6 harness](../scripts/phase6-load.py) executes the seven topology/cache/fault cases with actual counters, SQL reconciliation and sampled resources. [Results](performance/phase6-final/results.md) retain failed targets and invalid-window iterations; clock-suspension validity is not confused with application capacity. Indexed Maven-advisory and source-review evidence is in [performance review](performance/source-review.md). Native-container/build-plugin audits, actual lost-commit-ack experiments, physical clock-skew tolerance, backup/restore, maximum sustainable throughput and final operational acceptance remain unverified.
