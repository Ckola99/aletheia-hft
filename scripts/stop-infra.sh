#!/usr/bin/env bash
#
# stop-infra.sh — stops the Aletheia infrastructure containers.
# Data is preserved (uses 'stop', not 'down').
#
# Usage:  ./scripts/stop-infra.sh

echo "Stopping Aletheia infrastructure containers..."
docker compose -f docker/docker-compose.dev.yml stop

echo "Containers stopped. Data is preserved."
echo "Restart with:  ./scripts/start-infra.sh"
