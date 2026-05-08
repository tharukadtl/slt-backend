#!/usr/bin/env bash
# restart.sh — Kill any process on port 8080, then start the Spring Boot server.
# Usage: ./restart.sh

PORT=8080
LOG=logs/slt-fieldops.log

# Kill whatever is holding port 8080
PID=$(netstat -ano 2>/dev/null | awk "/:${PORT}.*LISTENING/{print \$5}" | head -1)
if [ -n "$PID" ]; then
    echo "Killing PID $PID on port $PORT..."
    taskkill //F //PID "$PID" 2>/dev/null
    sleep 1
else
    echo "Port $PORT is free."
fi

mkdir -p logs

echo "Starting server... (log: $LOG)"
./mvnw spring-boot:run > "$LOG" 2>&1 &

# Wait up to 60 seconds for startup
DEADLINE=$((SECONDS + 60))
while [ $SECONDS -lt $DEADLINE ]; do
    sleep 2
    if grep -q "Started Application" "$LOG" 2>/dev/null; then
        echo "Server is up on port $PORT."
        exit 0
    fi
    if grep -qE "APPLICATION FAILED|BUILD FAILURE" "$LOG" 2>/dev/null; then
        echo "Server failed to start. Check $LOG for details."
        exit 1
    fi
done

echo "Timed out waiting for server. Check $LOG for details."
exit 1
