# P6 source and dependency review

Date: 2026-10-11. Assistant review of the current public-API workspace; final human acceptance is pending. No remote deployment or engineer waiver is inferred.

| Area | Reviewed implementation and evidence | Disposition |
| --- | --- | --- |
| API scope | Spring Security/token gate removed by explicit user request. All management/statistics APIs are public; localhost ingress is the default | Scope retained; no account/ownership product added |
| Database secrets/roles | Separate bootstrap/runtime/migration passwords; `.env` ignored; fixture credentials private and removed; runtime role cannot migrate/delete retained mappings | Existing real-PostgreSQL privilege tests pass; no SQL/grant migration changed |
| URL/body policy | Absolute HTTP(S), no local/private literal/userinfo/self host, strict UTF-8/JSON/type/duplicate handling, 2,048-character URL and 16 KiB body bounds | Existing core/policy regressions retained; DNS/reputation is deliberately not claimed |
| SQL | Business repository, event recorder, stats and retention use JDBC bindings; seed/load SQL contains only controlled fixture values | No untrusted SQL interpolation introduced |
| Errors/logs | Safe API envelope/request ID; no raw exception/SQL/credential responses. Measurement whitelist exposes only meters/tags; separate-port regression verifies absent credentials/URL | Actual lock/timeout tests assert safe dependency errors; the aggregate GET timer includes every response status and excludes HEAD |
| Forwarding | NGINX overwrites forwarding and instance headers; app ignores forwarded interpretation and uses configured base URL; no unsafe POST retry enabled | Existing spoofed-host/instance tests and prior replacement topology evidence retained |
| Cache | Version/deadline validation, primary read timestamps, absolute TTL, strict/no-op, bounded Redis waits/breaker/fallback, no stale-after-deadline serving | Real Redis/cache/race tests pass; controlled warm fixture preserves deadlines |
| Async events | Bounded workers/admission/age, no caller-runs, no blind retry after unknown commit, explicitly lossy shutdown/crash behavior | Existing deterministic and actual child/HTTP-shutdown tests retained; measured slow-writer loss stays a failed healthy target |
| Measurement isolation | Explicit `measure` profile, whitelisted metrics, unpublished port 9090 in fixture; normal profile exposes no measurement endpoint | New separate-port/default-absence integration regression passes |
| Load accounting | Actual k6 scheduling/302/errors and instance counters; counter reset per app restart; SQL counts after bounded drain; sampled resource limits and raw evidence | No throughput inferred from topology; fault cases reported separately |
| Dependencies | OSV found 19 package/advisory pairs in the initial 122-package graph; managed minor-line patches applied; final query found zero | No finding suppressed or manually waived; known-indexed advisory scope only |

Patched BOM properties: Spring Framework 7.0.8 → 7.0.9, Tomcat 11.0.22 → 11.0.25, Netty 4.2.15.Final → 4.2.17.Final, Log4j 2.25.4 → 2.25.5, PostgreSQL JDBC 42.7.11 → 42.7.12, Jackson 3.1.4 → 3.1.7. Related modules upgrade together through their managed BOMs. The API/storage contract remains unchanged.

The exact primary advisory IDs, affected ranges and references are in [before audit](dependency-audit-before.json); the actual patched graph/result is in [after audit](dependency-audit.json). The [OSV API](https://google.github.io/osv.dev/api/) is the source of the automated gate. Some affected features are unused by this prototype, but the dependency versions were patched rather than declaring unsupported exploitability exceptions.

Limits: this is assistant source review plus indexed Maven-advisory matching, not a native-container/build-plugin audit or proof of exploitability/non-exploitability. Image digest pinning, hosted TLS, abuse controls, durable analytics, restoration, HA and final human acceptance remain separate work.
