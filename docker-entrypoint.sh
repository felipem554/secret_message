#!/bin/sh

# Memory hardening (docs/MEMORY_HARDENING.md):
# - JDWP lets a debugger read key material from the heap, so debug mode must
#   never reach production. Fail fast instead of silently ignoring it.
# - Core dumps would write heap pages (including key material) to disk.

if [ "$DEBUG" = "true" ] && [ "$APP_ENV" = "production" ]; then
    echo "FATAL: DEBUG=true is not allowed when APP_ENV=production (JDWP exposes heap memory)" >&2
    exit 1
fi

ulimit -c 0

if [ "$DEBUG" = "true" ]; then
    echo "Starting application in DEBUG mode on port 5005..."
    exec java $JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
else
    echo "Starting application..."
    exec java $JAVA_OPTS -jar app.jar
fi
