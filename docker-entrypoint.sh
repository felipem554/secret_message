#!/bin/sh

# Conditional debug mode for Java application
# Set DEBUG=true environment variable to enable remote debugging on port 5005

if [ "$DEBUG" = "true" ]; then
    echo "Starting application in DEBUG mode on port 5005..."
    exec java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
else
    echo "Starting application in PRODUCTION mode..."
    exec java -jar app.jar
fi
