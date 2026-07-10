#!/bin/sh
# CI guard against logging key material — docs/MEMORY_HARDENING.md, task 4.
#
# Fails when a logger call references a known secret-bearing variable or
# object. Deliberately targeted (not a naive ban on "key"): identifiers such
# as idempotency keys or message IDs are fine to log; AES key material and
# the objects that carry it are not.
#
# Usage: scripts/check-secret-logging.sh   (run from the repo root)
set -eu

PATTERN='log\.(trace|debug|info|warn|error)\([^)]*(aesKey|aeskey|keyBytes|masterKey|secretKey|getAeskey|CreateMessageResponse|SecretMessageIdentifier)'

MATCHES=$(grep -rnE "$PATTERN" src/main/java || true)

if [ -n "$MATCHES" ]; then
    echo "FAIL: logger call references key material or a key-bearing object:" >&2
    echo "$MATCHES" >&2
    exit 1
fi

echo "PASS: no logger call references key material"
