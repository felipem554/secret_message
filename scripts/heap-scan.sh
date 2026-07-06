#!/bin/sh
# Heap-dump marker scan — docs/MEMORY_HARDENING.md, Verification #1.
#
# Creates a secret message against a locally running instance, reveals it,
# floods the JSON/HTTP buffer recyclers with dummy traffic, forces GC, takes
# a LIVE-objects-only heap dump, and searches it for the AES key that was
# returned to the client:
#
#   1. as Base64 text  — would mean a reachable String copy of the key exists
#   2. as raw key bytes — would mean a reachable unwiped byte[] copy exists
#
# Both must yield zero hits. Because `jcmd GC.heap_dump` (without -all) dumps
# only reachable objects, any hit is a real leak on a long-lived object path,
# not dead garbage awaiting collection.
#
# Requirements:
#   - the app must run under a JDK-attachable JVM: start it locally WITHOUT
#     -XX:+DisableAttachMechanism (i.e. not the production container)
#   - jcmd on PATH, same user as the JVM process
#
# Usage: scripts/heap-scan.sh [app-url]     (default http://localhost:8080)
set -eu

APP_URL="${1:-http://localhost:8080}"

PID=$(jcmd -l | awk '/secret_message/ {print $1; exit}')
if [ -z "${PID:-}" ]; then
    echo "FAIL: no running secret_message JVM found (jcmd -l)" >&2
    exit 2
fi
echo "Target JVM pid: $PID"

MARKER="heap-scan-marker-$(date +%s)"
RESP=$(curl -sf -X POST "$APP_URL/api/v1/messages" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"$MARKER\"}")
KEY=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["aesKey"])')
MSG_ID=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["messageId"])')
echo "Created message $MSG_ID (key withheld from output)"

# Exercise the reveal path too, so its buffers are also part of the check.
curl -sf -o /dev/null -X POST "$APP_URL/api/v1/messages/reveal" \
    -H 'Content-Type: application/json' \
    -d "{\"messageId\":\"$MSG_ID\",\"aesKey\":\"$KEY\"}"

# Overwrite recycled Jackson/Tomcat I/O buffers with unrelated traffic.
# Pooled connection buffers legitimately hold the raw request/response bytes
# (which include the key) for one exchange; they must not survive reuse.
# Padding bodies are longer than any key-bearing request/response so partial
# overwrites cannot leave the key's tail behind, and both endpoints are hit
# so the reveal path's input buffers are flushed too. Note: this traffic
# counts against the per-IP rate limit (100/day by default).
PAD=$(printf 'x%.0s' $(seq 1 400))
i=0
while [ $i -lt 20 ]; do
    curl -sf -o /dev/null -X POST "$APP_URL/api/v1/messages" \
        -H 'Content-Type: application/json' \
        -d "{\"message\":\"$PAD\"}"
    curl -s -o /dev/null -X POST "$APP_URL/api/v1/messages/reveal" \
        -H 'Content-Type: application/json' \
        -d "{\"messageId\":\"pad-$PAD\",\"aesKey\":\"cGFkZGluZy1rZXktbm90LWEtcmVhbC1rZXktcGFkZGluZw==\"}"
    i=$((i + 1))
done

jcmd "$PID" GC.run > /dev/null
jcmd "$PID" GC.run > /dev/null

DUMP="$(mktemp -d)/heap-scan.hprof"
jcmd "$PID" GC.heap_dump "$DUMP" > /dev/null
echo "Heap dump written: $DUMP ($(du -h "$DUMP" | cut -f1))"

FAIL=0

BASE64_HITS=$(grep -a -c -F "$KEY" "$DUMP" || true)
if [ "$BASE64_HITS" -ne 0 ]; then
    echo "FAIL: Base64 key text found in $BASE64_HITS heap region(s) — a reachable String/char copy of the key exists"
    FAIL=1
else
    echo "PASS: Base64 key text not present in live heap"
fi

RAW_HITS=$(python3 - "$DUMP" "$KEY" << 'EOF'
import base64, sys
needle = base64.b64decode(sys.argv[2])
hits, tail = 0, b""
with open(sys.argv[1], "rb") as f:
    while True:
        chunk = f.read(1 << 24)
        if not chunk:
            break
        hits += (tail + chunk).count(needle)
        tail = chunk[-(len(needle) - 1):]
print(hits)
EOF
)
if [ "$RAW_HITS" -ne 0 ]; then
    echo "FAIL: raw key bytes found in $RAW_HITS heap region(s) — a reachable unwiped byte[] copy exists"
    FAIL=1
else
    echo "PASS: raw key bytes not present in live heap"
fi

# The plaintext marker is out of scope for the key-hardening plan (message
# plaintext handling is a separate work item) — report it as information only.
MARKER_HITS=$(grep -a -c -F "$MARKER" "$DUMP" || true)
echo "INFO: plaintext marker present in $MARKER_HITS heap region(s) (out of scope, expected > 0)"

rm -f "$DUMP"
exit $FAIL
