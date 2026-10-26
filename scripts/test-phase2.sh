#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Includes all Phase 1 regression tests and packages Order, Inventory and Product.
"$ROOT/scripts/test-phase1.sh" "$@"
for service in payment-service gateway; do
  (
    cd "$ROOT/$service"
    JWT_SECRET=phase2-only-test-jwt-secret-at-least-32-characters DB_PASSWORD='' bash ./mvnw -B "$@" \
      -Dspring.datasource.url="jdbc:postgresql://localhost:${PHASE1_POSTGRES_PORT:-55432}/orderflow_phase1" \
      -Dspring.datasource.driver-class-name=org.postgresql.Driver -Dspring.datasource.username=postgres -Dspring.datasource.password= \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect -Dspring.jpa.hibernate.ddl-auto=update \
      -Deureka.client.enabled=false -Dspring.kafka.listener.auto-startup=false -Dspring.jpa.show-sql=false -Dorderflow.recovery.enabled=false -Dspring.kafka.admin.auto-create=false verify
  )
done
