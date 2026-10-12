#!/usr/bin/env bash
# Explicit curl URLs, API purpose, expected responses and runnable validation.
# Start the app first, then:
#   bash scripts/api-curl-walkthrough.sh
# Optional: BASE_URL=http://localhost:8080
# Requires Bash, curl and Python 3. Creates two sample links, retains their records,
# disables one and lets the other expire. Does not follow redirects or stop services.
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
for tool in curl python3; do
  command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/api-curl.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
BODY="$WORK/response.json"
HEADERS="$WORK/response.headers"

KEY="curl-api-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"

# Log actual curl arguments immediately before sending, including every poll attempt.
# stderr keeps the transcript visible when stdout is captured as the HTTP status.
# The underlying curl calls below remain explicit and do not follow redirects.
CURL_USER_AGENT='urlshortener-curl-walkthrough/1.0'
curl() {
  python3 "$SCRIPT_DIR/curl-request-log.py" "$API_PURPOSE" --user-agent "$CURL_USER_AGENT" "$@" >&2
  command curl -q --user-agent "$CURL_USER_AGENT" "$@"
}

# Response helpers validate the output; each explicit call below sets its purpose.
section() { printf '\n========== %s ==========\n' "$1"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
expect_status() {
  echo "HTTP $STATUS (expected $1)"
  [ "$STATUS" = "$1" ] || { cat "$BODY" >&2; fail 'Unexpected HTTP status'; }
}
show_json() { python3 -m json.tool "$BODY"; }
json_value() {
  python3 - "$BODY" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))
for part in sys.argv[2].split('.'): value = value[part]
print('null' if value is None else value)
PY
}
header_value() {
  python3 - "$HEADERS" "$1" <<'PY'
import sys
values = [line.split(':', 1)[1].strip() for line in open(sys.argv[1])
          if ':' in line and line.split(':', 1)[0].lower() == sys.argv[2].lower()]
print(values[-1] if values else '')
PY
}

section 'Phase 0 — API contract'
echo 'Contract: openapi.yaml. This script tests current Phases 1–5 APIs.'
echo "Target: $BASE_URL. curl -q ignores personal curl settings; no -L is used."

section 'Phase 1 — Application and database health'

# GET /health/live: is the application process alive?
# Validate: HTTP 200 and JSON status="UP"; this does not prove database availability.
API_PURPOSE="Check process liveness"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/health/live")
expect_status 200; show_json
[ "$(json_value status)" = UP ] || fail 'Liveness must be UP'

# GET /health/ready: can this app reach PostgreSQL with the expected schema?
# Validate: HTTP 200/status="UP". Dependency/schema failure yields 503/status="DOWN".
API_PURPOSE="Check PostgreSQL/schema readiness"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/health/ready")
expect_status 200; show_json
[ "$(json_value status)" = UP ] || fail 'Readiness must be UP'

section 'Phase 2 — Core URLs, validation and idempotency'

# POST /api/v1/links: create an immutable short URL. The key makes retries replayable.
# Validate: HTTP 201; 8-character Base62 code, matching destination, null lifecycle fields.
# Save the generated CODE so subsequent commands do not need manual placeholders.
API_PURPOSE="Create a non-expiring keyed link"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/links" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  --data-raw '{"url":"https://example.com/curl-demo?source=api#section"}')
expect_status 201; show_json
CODE="$(json_value code)"
CREATION_INSTANCE="$(header_value X-App-Instance)"
python3 - "$BODY" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1]))
assert re.fullmatch('[A-Za-z0-9]{8}', d['code'])
assert d['url'] == 'https://example.com/curl-demo?source=api#section'
assert d['expiresAt'] is None and d['disabledAt'] is None
assert d['shortUrl'].endswith('/r/' + d['code'])
PY
echo "Saved CODE=$CODE; creation instance=${CREATION_INSTANCE:-unknown}"

# Identical POST/key: replay the original creation response instead of duplicating.
# Validate: HTTP 201 and the same code. A replay is deliberately not HTTP 200.
API_PURPOSE="Replay the same keyed creation"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/links" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  --data-raw '{"url":"https://example.com/curl-demo?source=api#section"}')
expect_status 201
[ "$(json_value code)" = "$CODE" ] || fail 'Replay must preserve the code'

# Changed POST/same key: reject conflicting reuse during the 24-hour key window.
# Validate: HTTP 409. The original mapping remains unchanged.
API_PURPOSE="Reject changed input with the same key"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/links" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  --data-raw '{"url":"https://example.com/changed"}')
expect_status 409; show_json

# GET /api/v1/links/{code}: current metadata, rather than a redirect.
# Validate: HTTP 200, same code/destination, disabledAt=null for this active link.
API_PURPOSE="Read active link metadata"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/api/v1/links/$CODE")
expect_status 200; show_json
[ "$(json_value code)" = "$CODE" ] || fail 'Metadata code mismatch'
[ "$(json_value disabledAt)" = null ] || fail 'New link must be active'

# GET /r/{code}: public redirect and one best-effort async analytics observation.
# Validate: HTTP 302, exact Location, Cache-Control: no-store, nonempty X-Request-Id.
# Do not add -L: that would fetch the external destination and hide the original 302.
API_PURPOSE="Resolve the public link and observe a GET attempt"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/$CODE")
expect_status 302
cat "$HEADERS"
[ "$(header_value Location)" = 'https://example.com/curl-demo?source=api#section' ] || fail 'Incorrect Location'
[ "$(header_value Cache-Control)" = no-store ] || fail 'Missing no-store'
[ -n "$(header_value X-Request-Id)" ] || fail 'Missing request ID'

# HEAD /r/{code}: same redirect policy without a body or analytics observation.
# Validate: HTTP 302 and Location. With -I, curl writes received headers to its output.
API_PURPOSE="Resolve HEAD without an analytics event"
STATUS=$(curl -q -sS --max-time 10 -I -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/$CODE")
expect_status 302; cat "$HEADERS"
[ "$(header_value Location)" = 'https://example.com/curl-demo?source=api#section' ] || fail 'Incorrect HEAD Location'

# POST /r/{code}: unsupported redirect method.
# Validate: HTTP 405 and Allow containing GET and HEAD.
API_PURPOSE="Reject an unsupported redirect method"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  -X POST "$BASE_URL/r/$CODE")
expect_status 405; show_json
ALLOW="$(header_value Allow)"
[[ "$ALLOW" == *GET* && "$ALLOW" == *HEAD* ]] || fail 'Allow must advertise GET and HEAD'

# Invalid redirect code: do not reveal internal details or redirect elsewhere.
# Validate: HTTP 404 and error.code=LINK_NOT_FOUND.
API_PURPOSE="Reject a malformed/missing redirect code"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/not-a-code")
expect_status 404; show_json
[ "$(json_value error.code)" = LINK_NOT_FOUND ] || fail 'Wrong missing-link error'

section 'Phase 3 — Optional expiration'

# POST with expiresAt: create a second link that expires five seconds from now (UTC).
# Validate: HTTP 201, non-null expiresAt, and HEAD 302 before its expiry.
EXPIRES_AT="$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)+timedelta(seconds=5)).isoformat(timespec="microseconds").replace("+00:00","Z"))')"
API_PURPOSE="Create a link with a five-second UTC expiry"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/links" -H 'Content-Type: application/json' \
  --data-raw "{\"url\":\"https://example.com/expiry-demo\",\"expiresAt\":\"$EXPIRES_AT\"}")
expect_status 201; show_json
EXPIRING_CODE="$(json_value code)"
[ "$(json_value expiresAt)" != null ] || fail 'Expiry missing'
API_PURPOSE="Check HEAD before expiry"
STATUS=$(curl -q -sS --max-time 10 -I -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/$EXPIRING_CODE")
expect_status 302

# Poll HEAD until expiry. No GET observations are added by polling.
# Validate: only 302 before expiry, then 410; GET error.code must be LINK_EXPIRED.
deadline=$((SECONDS + 12))
while :; do
  API_PURPOSE="Poll HEAD until expiry"
  STATUS=$(curl -q -sS --max-time 10 -I -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    "$BASE_URL/r/$EXPIRING_CODE")
  [ "$STATUS" = 410 ] && break
  expect_status 302
  [ "$SECONDS" -lt "$deadline" ] || fail 'Expiry not observed within 12 seconds'
  sleep 1
done
API_PURPOSE="Validate the expired GET error"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/$EXPIRING_CODE")
expect_status 410; show_json
[ "$(json_value error.code)" = LINK_EXPIRED ] || fail 'Wrong expiry error'

section 'Phase 4 — Eventual analytics and UTC date buckets'

# Two more public GETs: combined with the P2 GET, expect at least three observations.
# Validate each redirect is 302; do not equate requests with unique people/delivery.
for attempt in 1 2; do
  API_PURPOSE="Record observed GET attempt $attempt"
  STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    "$BASE_URL/r/$CODE")
  expect_status 302
done

# GET /stats: committed GET counts, asynchronously written to PostgreSQL.
# Validate: 200, observedRedirects >= 3 eventually, best-effort mode, daily sum=total.
# Immediate stats can lag; poll up to 15 seconds rather than assuming sync writes.
deadline=$((SECONDS + 15))
while :; do
  API_PURPOSE="Poll asynchronously committed stats"
  STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    "$BASE_URL/api/v1/links/$CODE/stats")
  expect_status 200
  [ "$(json_value observedRedirects)" -ge 3 ] && break
  [ "$SECONDS" -lt "$deadline" ] || fail 'Analytics lag/loss: three observations not visible within 15 seconds'
  sleep 1
done
show_json
python3 - "$BODY" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
assert d['recordingMode'] == 'best-effort-async' and d['eventuallyConsistent'] is True
assert d['observedRedirects'] == sum(b['observedRedirects'] for b in d['daily'])
PY

# GET /stats?from=YYYY-MM-DD&to=YYYY-MM-DD: explicit UTC half-open date range.
# Validate: HTTP 200 and response from/to equal the supplied dates (to is exclusive).
read -r FROM TO <<< "$(python3 -c 'from datetime import datetime,timedelta,timezone; d=datetime.now(timezone.utc).date(); print(d-timedelta(days=1),d+timedelta(days=1))')"
API_PURPOSE="Query an explicit UTC date range"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/api/v1/links/$CODE/stats?from=$FROM&to=$TO")
expect_status 200; show_json
[ "$(json_value from)" = "$FROM" ] && [ "$(json_value to)" = "$TO" ] || fail 'Date range mismatch'

# Missing to: invalid date ranges are rejected rather than silently defaulted.
# Validate: HTTP 400 and error.code=INVALID_DATE_RANGE.
API_PURPOSE="Reject an incomplete stats date range"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/api/v1/links/$CODE/stats?from=$FROM")
expect_status 400; show_json
[ "$(json_value error.code)" = INVALID_DATE_RANGE ] || fail 'Wrong date-range error'

section 'Phase 5 — Shared mapping through NGINX and lifecycle convergence'

# Repeated HEAD through the same ingress URL: show which replicas can resolve CODE.
# Validate: 302/no-store, observe X-App-Instance (app-1/app-2 in full Compose).
# Least-connections is not round-robin; observing one replica is not a failed API test.
# HTTP alone cannot establish Redis hit counts, PXAT TTL non-renewal or SQL reduction.
: > "$WORK/instances"
for attempt in {1..12}; do
  API_PURPOSE="Observe warm redirect replica $attempt"
  STATUS=$(curl -q -sS --max-time 10 -I -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    "$BASE_URL/r/$CODE")
  expect_status 302
  [ "$(header_value Cache-Control)" = no-store ] || fail 'Missing no-store'
  header_value X-App-Instance >> "$WORK/instances"
done
echo "Created on: ${CREATION_INSTANCE:-unknown}; resolution instances:"
sort -u "$WORK/instances"

# DELETE /api/v1/links/{code}: commit irreversible disable, then invalidate cache.
# This API belongs to P2; it is exercised here after all active-link/cache checks.
# Validate: HTTP 204 and zero body bytes. Repeating DELETE also returns 204.
for attempt in 1 2; do
  API_PURPOSE="Disable the link (attempt $attempt)"
  STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    -X DELETE "$BASE_URL/api/v1/links/$CODE")
  expect_status 204
  [ ! -s "$BODY" ] || fail '204 response must have an empty body'
done

# HEAD after disable: strict mode reads primary; cached mode may briefly return 302.
# Validate: eventual 410 within the documented 60-second freshness plus local margin.
# Original deadlines are not extended. This bound assumes synchronized runtime clocks.
deadline=$((SECONDS + 65))
while :; do
  API_PURPOSE="Poll cached-disable convergence"
  STATUS=$(curl -q -sS --max-time 10 -I -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
    "$BASE_URL/r/$CODE")
  [ "$STATUS" = 410 ] && break
  expect_status 302
  [ "$SECONDS" -lt "$deadline" ] || fail 'Disable failed to converge within 65 seconds'
  sleep 1
done
API_PURPOSE="Validate the disabled GET error"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/r/$CODE")
expect_status 410; show_json
[ "$(json_value error.code)" = LINK_INACTIVE ] || fail 'Wrong disabled-link error'

# GET metadata after disable: disabled rows remain available for management/history.
# Validate: HTTP 200, original code, and non-null disabledAt; DELETE did not erase it.
API_PURPOSE="Read retained disabled metadata"
STATUS=$(curl -q -sS --max-time 10 -D "$HEADERS" -o "$BODY" -w '%{http_code}' \
  "$BASE_URL/api/v1/links/$CODE")
expect_status 200; show_json
[ "$(json_value code)" = "$CODE" ] && [ "$(json_value disabledAt)" != null ] || fail 'Disabled metadata not retained'

section 'Phases 6–7 — Additional verification and handoff'
echo 'Fault/cache internals: python3 scripts/phase5-smoke.py (isolated Docker stack).'
echo 'Measured load, migration checks and final acceptance are separate from this API walkthrough.'
echo "PASS: API purposes/statuses/headers/JSON validated. Retained codes: $CODE $EXPIRING_CODE"
