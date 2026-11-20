#!/usr/bin/env bash
# Post-deploy smoke test for a RUNNING SafetyPad stack. Read-mostly: it registers ONE clearly named test device
# (SMOKE-<timestamp>) to prove the device path end to end; it never touches other data.
#
#   BASE_URL=https://monitor.example.com ADMIN_USER=... ADMIN_PASSWORD=... scripts/smoke.sh
#   BASE_URL=https://localhost:8443 CURL_OPTS=-k ...        # self-signed/internal CA rehearsal
set -u
BASE_URL="${BASE_URL:?set BASE_URL}"
ADMIN_USER="${ADMIN_USER:-}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
CURL_OPTS="${CURL_OPTS:-}"
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1  ($2)"; fail=$((fail+1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi
}
code() { curl -s $CURL_OPTS -o /dev/null -w '%{http_code}' "$@"; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

echo "== Edge / transport ($BASE_URL)"
HDRS="$(curl -s $CURL_OPTS -D - -o /dev/null "$BASE_URL/")"
echo "$HDRS" | head -1 | grep -q ' 200' && ok "GET / returns 200" || bad "GET / returns 200" "$(echo "$HDRS" | head -1)"
for h in strict-transport-security x-content-type-options x-frame-options content-security-policy referrer-policy; do
  echo "$HDRS" | grep -qi "^$h:" && ok "header $h present" || bad "header $h present" "missing"
done
echo "$HDRS" | grep -qi '^server:' && bad "Server header hidden" "present" || ok "Server header hidden"
check "health endpoint" '"status":"UP"' "$(curl -s $CURL_OPTS "$BASE_URL/actuator/health" | python3 -c 'import sys,json; print("\"status\":\""+json.load(sys.stdin)["status"]+"\"")' 2>/dev/null)"

echo "== Paths that must NOT be public"
for p in /actuator/prometheus /swagger.html /api-docs/openapi.yaml /api/v1/admin/dev/import-demo; do
  check "blocked $p" 404 "$(code "$BASE_URL$p")"
done

echo "== Device API boundary"
check "no key -> 401"      401 "$(code -X POST -H 'Content-Type: application/json' -d '{}' "$BASE_URL/api/v1/device-events")"
check "wrong key -> 401"   401 "$(code -X POST -H 'Content-Type: application/json' -H 'X-API-Key: spk_definitely_wrong' -d '{}' "$BASE_URL/api/v1/device-events")"
check "oversized body -> 413" 413 "$(head -c 20000 /dev/zero | tr '\0' 'x' | curl -s $CURL_OPTS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'X-API-Key: spk_x' --data-binary @- "$BASE_URL/api/v1/device-events")"
check "device key cannot open admin API" 401 "$(code -H 'X-API-Key: spk_definitely_wrong' "$BASE_URL/api/v1/admin/events")"

echo "== Admin boundary"
check "unauthenticated admin API -> 401" 401 "$(code "$BASE_URL/api/v1/admin/events")"
if [ -z "$ADMIN_USER" ] || [ -z "$ADMIN_PASSWORD" ]; then
  echo "  SKIP  admin checks (set ADMIN_USER / ADMIN_PASSWORD)"
else
  xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR"; }
  curl -s $CURL_OPTS -c "$JAR" -b "$JAR" "$BASE_URL/api/v1/admin/csrf" >/dev/null
  LOGIN="$(curl -s $CURL_OPTS -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' -X POST -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" "$BASE_URL/api/v1/admin/login")"
  check "admin login" 200 "$LOGIN"
  if [ "$LOGIN" = 200 ]; then
    P() { curl -s $CURL_OPTS -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' "$@"; }
    check "mutation without CSRF -> 403" 403 "$(curl -s $CURL_OPTS -b "$JAR" -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{"device_id":"X"}' "$BASE_URL/api/v1/admin/devices")"
    check "demo endpoint absent (404)" 404 "$(P -o /dev/null -w '%{http_code}' -X POST -d '{}' "$BASE_URL/api/v1/admin/dev/import-demo")"
    CFG="$(P "$BASE_URL/api/v1/admin/config")"
    check "demo tools disabled"  False "$(echo "$CFG" | json "d['demo_tools']")"
    check "route feature disabled" False "$(echo "$CFG" | json "d['route_enabled']")"
    check "no simulated data in production" False "$(P "$BASE_URL/api/v1/admin/summary" | json "d['simulated_data_present']")"

    echo "== End to end: device -> database -> admin"
    DEV="SMOKE-$(date +%s)"
    check "register test device" 201 "$(P -o /dev/null -w '%{http_code}' -X POST -d "{\"device_id\":\"$DEV\",\"alias\":\"smoke test\"}" "$BASE_URL/api/v1/admin/devices")"
    KEY="$(P -X POST -d '{"confirm":true}' "$BASE_URL/api/v1/admin/devices/$DEV/credentials" | json "d['api_key']")"
    [ -n "$KEY" ] && ok "issue device key" || bad "issue device key" "no key returned"
    NOW="$(date -u -d '-1 min' +%Y-%m-%dT%H:%M:%SZ)"
    EV="{\"event_id\":\"$DEV-1\",\"device_id\":\"$DEV\",\"event_type\":\"MULTI_RIDE\",\"event_time\":\"$NOW\",\"gpsData\":{\"latitude\":37.451448,\"longitude\":126.651542},\"battery_level\":77}"
    post() { curl -s $CURL_OPTS -X POST -H 'Content-Type: application/json' -H "X-API-Key: $KEY" -d "$EV" "$BASE_URL/api/v1/device-events"; }
    check "first POST accepted"      False "$(post | json "d['duplicate']")"
    check "retry acknowledged as duplicate" True "$(post | json "d['duplicate']")"
    check "exactly one stored event" 1 "$(P "$BASE_URL/api/v1/admin/events?device_id=$DEV" | json "d['total']")"
    P -X POST -d '{"confirm":true}' -o /dev/null "$BASE_URL/api/v1/admin/devices/$DEV/active" >/dev/null 2>&1
    P -X POST -d '{"active":false}' -o /dev/null "$BASE_URL/api/v1/admin/devices/$DEV/active"
    check "test device disabled afterwards -> POST 403" 403 "$(curl -s $CURL_OPTS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-API-Key: $KEY" -d "$EV" "$BASE_URL/api/v1/device-events")"
    echo "  NOTE  test device $DEV remains (disabled); raw events are immutable by design."
  fi
fi
echo "== Result: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
