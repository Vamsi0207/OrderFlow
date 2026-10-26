#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
docker compose -p orderflow-phase1-test -f "$ROOT/compose.phase1-test.yml" up -d --wait
# Use the wrapper and JAVA_HOME selected by the caller. Pass Maven settings/repository
# options as arguments, e.g. scripts/test-phase1.sh -s /path/to/settings.xml.
for service in order-service inventory-service product-service; do
  (
    cd "$ROOT/$service"
    JWT_SECRET=phase2-only-test-jwt-secret-at-least-32-characters DB_PASSWORD='' bash ./mvnw -B "$@" \
      -Dspring.datasource.url="jdbc:postgresql://localhost:${PHASE1_POSTGRES_PORT:-55432}/orderflow_phase1" \
      -Dspring.datasource.username=postgres -Dspring.datasource.password= \
      -Dspring.data.redis.host=localhost -Dspring.data.redis.port="${PHASE1_REDIS_PORT:-56379}" \
      -Deureka.client.enabled=false -Dspring.kafka.listener.auto-startup=false \
      -Dspring.jpa.show-sql=false -Dorderflow.recovery.enabled=false -Dspring.kafka.admin.auto-create=false verify
  )
done
