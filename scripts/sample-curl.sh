#!/usr/bin/env bash
# Sample end-to-end curl walkthrough, grouped by implementation phase.
# Usage: bash scripts/sample-curl.sh.
# Optional: BASE_URL=http://localhost:8080 SKIP_INGRESS_CHECK=1 (single-app baseline).
# Requires bash, curl and python3. Creates its own links and disables them afterward;
# mappings/idempotency records are retained by design. Never follows redirects.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
SKIP_INGRESS_CHECK="${SKIP_INGRESS_CHECK:-0}"
for tool in curl python3; do
  command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/urlshortener-curl.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
KEY="sample-core-$RUN_ID"
EXPIRY_KEY="sample-expiry-$RUN_ID"

section() { printf '\n========== %s ==========\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
field() {
  python3 - "$WORK/body" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))[sys.argv[2]]
print(json.dumps(value) if value is None or isinstance(value, (bool, dict, list)) else value)
PY
}
header() {
  python3 - "$WORK/headers" "$1" <<'PY'
import sys
values = [line.split(':', 1)[1].strip() for line in open(sys.argv[1])
          if ':' in line and line.split(':', 1)[0].lower() == sys.argv[2].lower()]
print(values[-1] if values else '')
PY
}

# Each call below is a curl request. Expected status is explicit; bodies and headers
# are saved for the next assertion. No -L, no automatic write retries, bounded waits.
# request LABEL EXPECTED METHOD PATH [JSON] [IDEMPOTENCY_KEY]
request() {
  local label="$1" expected="$2" method="$3" route="$4"
  local body="${5:-}" key="${6:-}"
  local args=(--silent --show-error --connect-timeout 2 --max-time 10
    --dump-header "$WORK/headers" --output "$WORK/body" --write-out '%{http_code}')
  if [ "$method" = HEAD ]; then args+=(--head); else args+=(--request "$method"); fi
  if [ -n "$body" ]; then args+=(-H 'Content-Type: application/json' --data-raw "$body"); fi
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $key"); fi
  python3 "$SCRIPT_DIR/curl-request-log.py" "$label" --user-agent 'urlshortener-curl-walkthrough/1.0' "${args[@]}" "$BASE_URL$route" >&2
  STATUS="$(curl -q --user-agent 'urlshortener-curl-walkthrough/1.0' "${args[@]}" "$BASE_URL$route")" || fail "$label: transport failure"
  printf '%-43s %s %s -> %s\n' "$label" "$method" "$route" "$STATUS"
  if [ "$expected" != any ] && [ "$STATUS" != "$expected" ]; then
    cat "$WORK/body" >&2
    fail "$label: expected HTTP $expected, got $STATUS"
  fi
  if [ "$method" = HEAD ] || [ "$STATUS" = 204 ]; then
    # curl -I writes headers to its output file, so bodylessness is checked separately.
    if [ "$method" != HEAD ]; then [ ! -s "$WORK/body" ] || fail '204 must have an empty body'; fi
  elif [ -s "$WORK/body" ]; then
    python3 -m json.tool "$WORK/body" 2>/dev/null || cat "$WORK/body"
  fi
}

section 'Phase 0 — Contracts and scope'
echo 'Runtime checks below relate to Phases 1–5. API contract: openapi.yaml.'
echo 'No rate limits/429 expectation; analytics are eventual and best effort.'
echo "Target: $BASE_URL (redirect destinations are never fetched)."

section 'Phase 1 — Foundation and health'
request 'Process liveness' 200 GET /health/live
[ "$(field status)" = UP ] || fail 'Liveness is not UP'
request 'Database/schema readiness' 200 GET /health/ready
[ "$(field status)" = UP ] || fail 'Readiness is not UP'
if [ "$SKIP_INGRESS_CHECK" != 1 ]; then
  request 'NGINX ingress health' 200 GET /health/ingress
else
  echo 'SKIP NGINX ingress health: single-app baseline selected.'
fi

section 'Phase 2 — Core APIs, validation and idempotency'
request 'Reject a private destination' 400 POST /api/v1/links '{"url":"http://192.168.1.1/private"}'
request 'Create a non-expiring link' 201 POST /api/v1/links '{"url":"https://example.com/sample?source=curl#demo"}' "$KEY"
CODE="$(field code)"
CREATED_BY="$(header X-App-Instance)"
echo "Created code $CODE on instance ${CREATED_BY:-unknown}."
request 'Replay same idempotent request' 201 POST /api/v1/links '{"url":"https://example.com/sample?source=curl#demo"}' "$KEY"
[ "$(field code)" = "$CODE" ] || fail 'Replay changed the code'
request 'Conflicting idempotent request' 409 POST /api/v1/links '{"url":"https://example.com/different"}' "$KEY"
request 'Inspect current metadata' 200 GET "/api/v1/links/$CODE"
[ "$(field disabledAt)" = null ] || fail 'New link is disabled'
request 'Public GET redirect' 302 GET "/r/$CODE"
[ "$(header Location)" = 'https://example.com/sample?source=curl#demo' ] || fail 'Wrong redirect destination'
[ "$(header Cache-Control)" = no-store ] || fail 'Missing no-store'
request 'Public HEAD redirect' 302 HEAD "/r/$CODE"
request 'Unsupported redirect method' 405 POST "/r/$CODE"
request 'Malformed/missing redirect code' 404 GET /r/not-a-code

section 'Phase 3 — Additive expiration'
EXPIRES_AT="$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)+timedelta(seconds=5)).isoformat(timespec="microseconds").replace("+00:00","Z"))')"
EXPIRY_BODY="{\"url\":\"https://example.com/expiring\",\"expiresAt\":\"$EXPIRES_AT\"}"
request 'Create an expiring link' 201 POST /api/v1/links "$EXPIRY_BODY" "$EXPIRY_KEY"
EXPIRING_CODE="$(field code)"
request 'HEAD before expiry' 302 HEAD "/r/$EXPIRING_CODE"
until_seconds=$((SECONDS + 12))
while :; do
  request 'Poll expiry without analytics events' any HEAD "/r/$EXPIRING_CODE"
  [ "$STATUS" = 410 ] && break
  [ "$STATUS" = 302 ] || fail "Unexpected expiry status $STATUS"
  [ "$SECONDS" -lt "$until_seconds" ] || fail 'Expiry did not converge within 12 seconds'
  sleep 1
done
request 'Expired GET error envelope' 410 GET "/r/$EXPIRING_CODE"
request 'Replay original after expiry' 201 POST /api/v1/links "$EXPIRY_BODY" "$EXPIRY_KEY"
[ "$(field code)" = "$EXPIRING_CODE" ] || fail 'Expired replay changed code'
request 'Reject expiry in the past' 400 POST /api/v1/links '{"url":"https://example.com/past","expiresAt":"2000-01-01T00:00:00Z"}'

section 'Phase 4 — Asynchronous analytics and UTC stats'
request 'Create an isolated analytics link' 201 POST /api/v1/links '{"url":"https://example.com/analytics"}' "sample-stats-$RUN_ID"
ANALYTICS_CODE="$(field code)"
for number in 1 2 3; do request "Observed GET attempt $number" 302 GET "/r/$ANALYTICS_CODE"; done
request 'HEAD does not submit an event' 302 HEAD "/r/$ANALYTICS_CODE"
until_seconds=$((SECONDS + 15))
while :; do
  request 'Poll committed eventual stats' 200 GET "/api/v1/links/$ANALYTICS_CODE/stats"
  [ "$(field observedRedirects)" -ge 3 ] && break
  [ "$SECONDS" -lt "$until_seconds" ] || fail 'Three events not observed within 15s; inspect best-effort queue/DB losses or lag'
  sleep 1
done
python3 - "$WORK/body" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
assert s['recordingMode'] == 'best-effort-async' and s['eventuallyConsistent'] is True
assert s['observedRedirects'] == sum(d['observedRedirects'] for d in s['daily'])
PY
read -r FROM TO <<< "$(python3 -c 'from datetime import datetime,timedelta,timezone; d=datetime.now(timezone.utc).date(); print(d-timedelta(days=1), d+timedelta(days=1))')"
request 'Explicit UTC range [from,to)' 200 GET "/api/v1/links/$ANALYTICS_CODE/stats?from=$FROM&to=$TO"
request 'Reject an incomplete date range' 400 GET "/api/v1/links/$ANALYTICS_CODE/stats?from=$FROM"

section 'Phase 5 — Replica observations and cache lifecycle'
: > "$WORK/instances"
for number in {1..20}; do
  request "Warm HEAD via ingress $number" 302 HEAD "/r/$CODE"
  [ "$(header Cache-Control)" = no-store ] || fail 'Ingress cached HTTP redirects'
  header X-App-Instance >> "$WORK/instances"
done
python3 - "$WORK/instances" "$CREATED_BY" <<'PY'
import sys
instances = sorted(set(open(sys.argv[1]).read().split()))
print('Observed redirect replicas:', ', '.join(instances) or '(header absent)')
print('Creation replica:', sys.argv[2] or '(header absent)')
if len(instances) < 2:
    print('NOTE: no two-replica evidence in this run; check topology/routing. Least-connections does not promise alternation.')
PY
echo 'HTTP alone cannot prove Redis hits, PXAT non-renewal or reduced SQL; phase5-smoke.py/CacheIT cover those.'
request 'Disable primary link' 204 DELETE "/api/v1/links/$CODE"
request 'Repeated disable is idempotent' 204 DELETE "/api/v1/links/$CODE"
until_seconds=$((SECONDS + 65))
while :; do
  request 'Poll bounded cached-disable convergence' any HEAD "/r/$CODE"
  [ "$STATUS" = 410 ] && break
  [ "$STATUS" = 302 ] || fail "Unexpected disable status $STATUS"
  [ "$SECONDS" -lt "$until_seconds" ] || fail 'Disable did not converge within 65 seconds'
  sleep 1
done
request 'Retained disabled metadata' 200 GET "/api/v1/links/$CODE"
[ "$(field disabledAt)" != null ] || fail 'Missing disabledAt'
request 'Replay preserves original creation result' 201 POST /api/v1/links '{"url":"https://example.com/sample?source=curl#demo"}' "$KEY"
[ "$(field code)" = "$CODE" ] && [ "$(field disabledAt)" = null ] || fail 'Wrong original replay result'
request 'Replay does not reactivate the link' 410 GET "/r/$CODE"
request 'Disable analytics sample link' 204 DELETE "/api/v1/links/$ANALYTICS_CODE"

section 'Phases 6–7 — Separate reliability/load and handoff work'
echo 'This walkthrough does not stop services, measure throughput, test migrations or certify final acceptance.'
echo 'Disposable fault/topology checks: python3 scripts/phase5-smoke.py'
echo 'Full unit/integration gates: ./mvnw -B -ntp -Dmaven.repo.local=.m2-cache verify'
echo "PASS: curl walkthrough. Retained sample codes: $CODE $EXPIRING_CODE $ANALYTICS_CODE"
