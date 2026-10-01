# ledger-service

Spring Boot 3 / Java 21 backend. See the [root README](../README.md) for the overview,
architecture and how to run the whole stack; [API_SPEC.md](API_SPEC.md) for the contract.

```bash
docker compose up -d                       # Postgres + Redis only (this folder)
APP_ADMIN_USERNAME=admin APP_ADMIN_PASSWORD=admin-password-123 APP_DEMO_MODE=true \
  mvn spring-boot:run                      # API on :8080, Swagger UI at /swagger-ui.html

mvn spring-boot:run -Dspring-boot.run.profiles=no-redis   # no Redis: in-memory rate limiter
mvn verify                                 # unit + integration tests (Docker needed for the latter)
```

Configuration is environment-driven (`application.yml` lists every knob):
`APP_JWT_SECRET`, `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`, `APP_DEMO_MODE`,
`APP_CORS_ALLOWED_ORIGINS`, `APP_SWAGGER_ENABLED`, `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_*`.

Tests that need Docker (Testcontainers) are skipped automatically when Docker isn't available.
