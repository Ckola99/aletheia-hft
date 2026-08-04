#!/usr/bin/env bash
#
# start-infra.sh — starts the Aletheia development infrastructure
# (TimescaleDB, Redis, Prometheus, Grafana, Adminer)
#
# Usage:  ./scripts/start-infra.sh

set -e  # stop immediately if any command fails

echo "Starting Aletheia infrastructure containers..."
docker compose -f docker/docker-compose.dev.yml up -d

echo ""
echo "Containers started. Current status:"
docker compose -f docker/docker-compose.dev.yml ps
