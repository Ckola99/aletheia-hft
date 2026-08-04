#!/usr/bin/env bash
#
# run-engine.sh — starts the Aletheia live trading engine.
# Checks that infrastructure and credentials are ready first.
#
# Usage:  ./scripts/run-engine.sh

set -e

# 1. Make sure the database is up before we start
if ! docker exec aletheia-timescaledb pg_isready -U alethia >/dev/null 2>&1; then
    echo "ERROR: TimescaleDB is not running."
    echo "Start infrastructure first:  ./scripts/start-infra.sh"
    exit 1
fi

# 2. Make sure OANDA credentials are set
if [ -z "$OANDA_API_KEY" ] || [ -z "$OANDA_ACCOUNT_ID" ]; then
    echo "ERROR: OANDA credentials not set."
    echo "Set them with:"
    echo "  export OANDA_API_KEY=\"your-key\""
    echo "  export OANDA_ACCOUNT_ID=\"your-account\""
    exit 1
fi

echo "Infrastructure OK, credentials OK."
echo "Starting Aletheia trading engine..."
echo "Press Ctrl+C to stop."
echo ""

./mvnw spring-boot:run -pl aletheia-api
