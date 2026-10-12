# Scenario B: Brownfield Expiration Upgrade

Status: Phase 3 implemented; clean unit/PostgreSQL verification passed on 2026-10-11. Packaged saved-P2/V1 → P3/V2 upgrade, retained restart/outage and expiry checks passed. Human migration/product acceptance pending.

## Intent and baseline

Add optional immutable expiration to a functioning P2 service without changing old identities, destinations or lifecycle/key state. Source baseline: `b098f8f87cbd3d64580b4db174bda24af609b1d6`, recorded in [greenfield](greenfield.md). Current API: [P3 checkpoint](../openapi-phase3.yaml); prior P2 contract remains archived. No Redis or analytics is added in this phase.

## Decomposition and impact map

1. Preserve V1 and add nullable V2 storage/check.
2. Extend request parsing/response/domain records, UTC validation and canonical fingerprint.
3. Extend JDBC INSERT and projections, keeping original keys byte-compatible.
4. Replay a matching live key before new-creation future checks; compare new expiry against the same captured microsecond creation time used by SQL.
5. Check disabled then expired state on both GET and HEAD; metadata retains expiry after lifecycle changes.
6. Update migration target/readiness/startup guard to 2; verify V1 upgrade, exact time/offset/precision boundaries, existing core/race/rollback behavior and packaged retained-volume restart.

## Before/after execution evidence

- Before: P2 input above, V1 links/key tables with no expiry. Testcontainers seeds fixed active/disabled UUID rows with original timestamps/URLs plus golden P2 key digest/fingerprint before V2. Docker smoke builds the actual saved P2 source, creates active/disabled/keyed links through HTTP, then switches app/migrator build on the same isolated database volume.
- Migration: [V2 SQL](../../src/main/resources/db/migration/V2__add_link_expiration.sql). It adds nullable `expires_at` and `ck_links_expiry_after_creation`; existing rows stay null. [V1](../../src/main/resources/db/migration/V1__core_links_and_idempotency.sql) is unchanged; SHA-256 `3d04a7ff7bd1a1f891ebf5a1ef3205a7f448a3bd7883b0f0de3f8a547e6600ef`.
- Changed flow: `ExpirationPolicy`, `Link`/`LinkResponse`, `Idempotency`, `LinkService`, `LinkRepository`, `LinkController`; target/readiness/guard/config; core/foundation tests and staged API/setup/evidence docs.
- Diff inspection: `git diff b098f8f -- src/main src/test` (or use the recorded after checkpoint once committed). No V1 diff, key-role expansion or table reset. No destination/expiry edit endpoint.
- AI dispositions and actual review/debugging: P3-001 in [AI log](../ai-execution-log.md).
- After source checkpoint: `20b9159ef8a35accfb42c2165141d2f1b49acbe2` (`20b9159`), local commit `Implement Phase 3 additive link expiration`. Compare `git diff b098f8f 20b9159 -- src/main src/test` for the application/migration/test change. Subsequent evidence-only edits do not change the tested application.

## Validation evidence

- Clean BUILD SUCCESS: 76 unit + 28 PostgreSQL integration tests, zero failures/errors/skips, zero style violations. Includes all P2 code/key collision, stale replacement, permission failure and rollback regressions.
- V1 baseline → V2: old UUIDs/URLs/creation/disable state and null-expiry key replay preserved; runtime remains restricted. Current app is unready with V1 alone and ready after packaged V2 migration.
- RED-09–16 / DB-02 / IDE-05/10: absent/null expiry, future/past/equal/malformed input, no offset, leap seconds, range conversion, six-digit limit, UTC microseconds, offset equivalence, before/at expiry, bodyless HEAD, disabled precedence and retained expired mappings. Matching replay after expiry succeeds; changed expiry conflicts. Very short valid creation can return already expired.
- Packaged migration/restart/outage/expiry result: PASS; old identities/URLs/timestamps/disabled state, key records and V1 checksum unchanged immediately after upgrade. Active/disabled/keyed and newly expired state survive restart, and migration replay/outage recovery pass. Own test containers/volume were disposed; see P3-VAL-07 in [validation results](../validation-results.md).

## Review, recovery and limitations

Assistant reviewed the additive SQL, impacted flow, precision/replay order, least privilege, tests and source diff. Human review is pending. Failed migrations must be investigated with storage/history preserved; no automatic repair or destructive recovery is implemented. Flyway history is validated and V1 remains unchanged. Old P2 code ignores expiry, so this coordinated local upgrade is not a safe rolling serving downgrade after expiring rows exist. No production recovery, backup/restore, load, cache, analytics or interrupted-DDL claim.
