# Ledger Service — API reference

The live, always-correct reference is the generated OpenAPI document:
**Swagger UI** at `/swagger-ui.html`, raw spec at `/v3/api-docs`. This file is the
human summary of the contract and its guarantees.

Base path `/api/v1`. JSON in/out. Authenticated endpoints need `Authorization: Bearer <access token>`.

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/auth/register` | public | Create user + account, returns tokens |
| POST | `/auth/login` | public | Returns tokens |
| POST | `/auth/refresh` | public (refresh token) | Rotate refresh token, new access token |
| POST | `/auth/logout` | public (refresh token) | Revoke a refresh token |
| GET  | `/meta` | public | `{demoMode, version}` |
| GET  | `/accounts/{id}/balance` | owner / admin | Cached balance |
| GET  | `/accounts/{id}/entries?page=&size=` | owner / admin | Newest-first page, each row with `runningBalance` |
| GET  | `/accounts/{id}/audit` | owner / admin | Replay the log vs. cached balance |
| POST | `/accounts/{id}/debits` | owner / admin | Redeem points (rate limited) |
| POST | `/accounts/{id}/credits` | admin (owner in demo mode) | Credit points |
| POST | `/accounts/{id}/entries/{entryId}/reverse` | admin (owner in demo mode) | Compensating entry |
| POST | `/accounts` | admin | Provision an account without a login |
| GET  | `/admin/accounts?q=&page=&size=` | admin | Search all accounts |

Operational: `/actuator/health` (public), `/actuator/prometheus` and other actuator endpoints (ADMIN).

## Idempotency

`credits`, `debits` and `reverse` require an `idempotencyKey` (unique **per account**).

| Situation | Response |
|---|---|
| First time | `201` + the new entry |
| Same key, same request (retry) | `200` + the **original** entry; nothing is applied twice |
| Same key, different type/amount | `409 IDEMPOTENCY_KEY_REUSE` |
| Two identical requests racing | one `201`, the other `200` (unique constraint decides) |

## Errors

Every failure has the same shape; `code` is stable, `message` is for humans.

```json
{
  "timestamp": "2026-10-01T10:15:30Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "INSUFFICIENT_BALANCE",
  "message": "Insufficient balance: have 40.00, need 100.00",
  "requestId": "6b1f…"
}
```

Validation errors add `"fieldErrors": {"amount": "amount must be at least 0.01"}`.
Send `X-Request-Id` to supply your own correlation id; it is echoed on every response and appears in every log line.

| Status | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` | Bad input |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` | Missing/expired token, bad login |
| 403 | `ACCESS_DENIED` | Not your account, or admin-only action |
| 404 | `ACCOUNT_NOT_FOUND` | |
| 409 | `USERNAME_TAKEN`, `IDEMPOTENCY_KEY_REUSE`, `INVALID_REVERSAL`, `CONCURRENT_UPDATE`, `DATA_CONFLICT` | State conflicts; `CONCURRENT_UPDATE` is safe to retry with the same key |
| 422 | `INSUFFICIENT_BALANCE` | |
| 429 | `RATE_LIMITED` | `Retry-After` header set |
| 500 | `INTERNAL_ERROR` | Details only in server logs, matched by `requestId` |

## Money rules

* Amounts: `0.01` ≤ amount, max 2 decimal places, `NUMERIC(19,2)` in the database.
* A debit can never take a balance below zero (application check **and** a DB `CHECK`).
* Reversing a credit re-checks the balance — the points may already be spent.
* An entry can be reversed once; a reversal entry cannot itself be reversed.
