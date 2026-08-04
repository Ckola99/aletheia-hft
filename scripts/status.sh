#!/usr/bin/env bash
#
# status.sh — shows the status of all Aletheia infrastructure
# and checks whether each service is actually responding.
#
# Usage:  ./scripts/status.sh

echo "=== Container status ==="
docker compose -f docker/docker-compose.dev.yml ps

echo ""
echo "=== Service health checks ==="

# TimescaleDB — ask Postgres if it's ready to accept connections
if docker exec aletheia-timescaledb pg_isready -U alethia >/dev/null 2>&1; then
    echo "  TimescaleDB   : UP (accepting connections)"
else
    echo "  TimescaleDB   : DOWN"
fi

# Redis — send PING, expect PONG
if [ "$(docker exec aletheia-redis redis-cli ping 2>/dev/null)" = "PONG" ]; then
    echo "  Redis         : UP (PONG)"
else
    echo "  Redis         : DOWN"
fi

# Grafana, Prometheus, Adminer — hit their HTTP ports
check_http () {
    local name="$1"
    local url="$2"
    if curl -s -o /dev/null -w "%{http_code}" "$url" | grep -qE "200|302"; then
        echo "  $name : UP ($url)"
    else
        echo "  $name : DOWN ($url)"
    fi
}

check_http "Grafana      " "http://localhost:3000/api/health"
check_http "Prometheus   " "http://localhost:9090/-/healthy"
check_http "Adminer      " "http://localhost:8081"
