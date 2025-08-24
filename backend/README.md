# Euler Backend (Spring Boot + MongoDB + Web3j, Gradle)

Сервис опрашивает eVault-пары на бэкенде, считает ROE/HF и пишет в MongoDB.

## Запуск (локально)
```bash
./gradlew bootRun
```
Переменные окружения (можно задать через .env / shell):
- `EULER_RPC_URL` (по умолчанию Avalanche)
- `EULER_UTILS_LENS`
- `MONGODB_URI`

## Docker / Compose
```bash
docker compose up -d --build
```
Swagger UI: http://localhost:8080/swagger-ui/index.html

## REST
- `GET /api/v1/pairs`
- `POST /api/v1/pairs`
- `PATCH /api/v1/pairs/{id}/enable?enabled=true|false`
- `DELETE /api/v1/pairs/{id}`
- `GET /api/v1/snapshots`
