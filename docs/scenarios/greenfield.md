# Scenario A: Greenfield Core Service

Status: Phase 2 implemented and verified on 2026-10-11. Assistant review complete; human engineer acceptance pending. This is the source baseline for Phase 3, not the completed assignment.

## Intent and acceptance

Build create/inspect/GET/HEAD/disable APIs against PostgreSQL before expiry/caching. Verify public API access, temporary no-store redirects, unknown/inactive behavior, transactional code/key races, and retained committed state. See P2 in [implementation plan](../implementation-plan.md), [P2 OpenAPI](../openapi-phase2.yaml) and CRT/IDE/RED/SEC cases in [edge catalog](../edge-case.md).

## Decomposition and dependencies

1. Keep P1/V1 database, health, role and build foundation.
2. Validate UTF-8 JSON/body sizes, destination URLs and optional key headers.
3. Generate eight-character codes; map immutable links and key records with JDBC.
4. Apply atomic link/key transactions, named uniqueness classification and bounded fresh-transaction retries; arbitrate stale-key replacement under conditional DELETE locking.
5. Expose metadata/irreversible disable and public explicit GET/HEAD.
6. Validate unit/HTTP/real database races, package/start/restart/outage behavior; capture the baseline before nullable expiry is introduced.

## Execution evidence

- Baseline source revision/commit: `b098f8f87cbd3d64580b4db174bda24af609b1d6` (`b098f8f`), local commit `Implement Phase 2 core URL shortener baseline`. Includes the tested source, V1 migration, API checkpoint and setup artifacts; no remote push. Subsequent evidence-only documentation corrections do not change the baseline application.
- Implemented modules: `src/main/java/com/example/urlshortener/link`, `api`, `controller/LinkController.java`, validated web configuration. V1 schema is retained without expiry or events.
- Tests: `DestinationPolicyTest`, `RandomCodeGeneratorTest`, `IdempotencyTest`, `CoreApiIT` plus P1 regression tests. Disposable packaged verification: `scripts/phase2-smoke.py`.
- AI prompts/iterations/dispositions: actual P2-001 in [AI log](../ai-execution-log.md).
- Debugging example: PostgreSQL rejected key `FOR UPDATE` under the existing role. Replaced it with a conditional stale DELETE rather than expanding privileges or rewriting V1; then verified concurrent expiry replacement and rollback.

## Validation evidence

- Clean Maven verification: 54 unit + 22 PostgreSQL integration tests passed; zero failures/errors/skips and zero Checkstyle violations. Exact command/environment: P2-VAL-01 in [validation results](../validation-results.md).
- URL-01–11 / HTTP-01–03: protocol/host/literal/self-host/content-preservation/size/strict-parser tests. No external DNS or destination fetch.
- CRT-01–08 / IDE-01–04,06–07,12: injected alphabet boundaries, forced code/key races, five-collision exhaustion, retained disabled codes, original replay, key format/digests, mismatch/loser rollback. Failure after link INSERT before key INSERT leaves no orphan candidate.
- 24-hour half-open boundary and four-client expired-key replacement verified using injected UTC clock; no cleanup process is required to enforce the window.
- Redirect/security tests: create → inspect → GET/HEAD → disable; exact no-store/Location/status, bodyless HEAD/204, repeated disable, missing/trailing/extra routes, request errors, unconfigured CORS and configured base URL despite forwarded-host input.
- Packaged Compose: created link and key persist through app/PostgreSQL restart; outage returns safe dependency error and ready DOWN/live UP, recovery restores reads; migration replay preserves data. Disable/keyed original replay persist across another restart. Only the generated fixture project/volume was removed.

## Review and limitations

- Assistant review: implementation, SQL retry/rollback boundaries, API staging, output redaction, mandatory quality gates and evidence inspected.
- Human engineer review/sign-off: pending; no stakeholder acceptance is inferred from passing tests.
- No expiry, stats, async event writer, Redis or NGINX/two-replica routing at this checkpoint. No throughput, exact-delivery, DNS safety, audit, failover or backup claim. Lost/uncertain creation responses use keyed recovery; unkeyed retries can duplicate.
- Phase 3 must preserve V1 rows/codes/destinations, retain existing null-expiry fingerprints, add nullable expiry via V2, update readiness/migration targets and run brownfield regression against this saved baseline.
