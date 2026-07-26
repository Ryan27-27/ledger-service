# Ledger Service — API Specification

Base URL: `http://localhost:8080/api/v1`

All amounts are decimal strings/numbers with 2 decimal places (points, treated like currency — no float rounding errors).

**Every endpoint below except `/auth/register` and `/auth/login` requires an
`Authorization: Bearer <token>` header.** Tokens are issued by register/login
and expire after 1 hour (`app.jwt.expiry-seconds`). A request to
`/accounts/{accountId}/...` where `{accountId}` doesn't match the token's own
account is rejected with `403`, even for a validly-authenticated user — see
the ownership note at the end of this doc.

---

## 0. Register

`POST /auth/register`

Creates a `User` and a matching points `Account` together, and returns a JWT.

**Request**
```json
{ "username": "aryan123", "password": "correcthorsebattery" }
```

**Response `201 Created`**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "accountId": "b3f1e2a0-...",
  "username": "aryan123"
}
```

**Errors**
| Status | Cause |
|---|---|
| 400 | validation failure (blank username, password under 8 chars) |
| 409 | username already taken |

---

## 0.1 Login

`POST /auth/login`

**Request**
```json
{ "username": "aryan123", "password": "correcthorsebattery" }
```

**Response `200 OK`** — same shape as register.

**Errors**
| Status | Cause |
|---|---|
| 401 | wrong username or password |

---

## 1. Create Account (admin only)

`POST /accounts`

Direct account provisioning outside of registration — for ops tooling or
service accounts. Requires the caller's token to carry the `ADMIN` role.
Ordinary users get an account automatically via `/auth/register` and never
need this endpoint.

**Request**
```json
{ "userId": "user-123" }
```

**Response `201 Created`**
```json
{
  "id": "b3f1e2a0-...",
  "userId": "user-123",
  "cachedBalance": 0.00,
  "version": 0,
  "createdAt": "2026-07-20T10:00:00Z",
  "updatedAt": "2026-07-20T10:00:00Z"
}
```

**Errors**
| Status | Cause |
|---|---|
| 403 | caller does not have the ADMIN role |

---

## 2. Credit Points (earn)

`POST /accounts/{accountId}/credits`

**Request**
```json
{
  "amount": 50.00,
  "referenceId": "bill-payment-98234",
  "idempotencyKey": "credit-bill-98234-v1"
}
```

**Response `201 Created`** (new entry) or **`200 OK`** (idempotent replay — same key seen before, original entry returned unchanged)
```json
{
  "id": "e7c2...",
  "accountId": "b3f1e2a0-...",
  "amount": 50.00,
  "type": "CREDIT",
  "status": "POSTED",
  "referenceId": "bill-payment-98234",
  "createdAt": "2026-07-20T10:05:00Z"
}
```

**Errors**
| Status | Cause |
|---|---|
| 400 | validation failure (missing/negative amount, blank referenceId) |
| 403 | token's account does not match `{accountId}` in the URL |
| 404 | account not found |
| 409 | concurrent update conflict (optimistic lock) — client should retry |

---

## 3. Debit Points (redeem)

`POST /accounts/{accountId}/debits`

**Request**
```json
{
  "amount": 30.00,
  "referenceId": "redemption-55123",
  "idempotencyKey": "debit-redemption-55123-v1"
}
```

**Response `201 Created`** / **`200 OK`** (idempotent replay) — same shape as credit response, `"type": "DEBIT"`.

**Errors**
| Status | Cause |
|---|---|
| 400 | validation failure |
| 403 | token's account does not match `{accountId}` in the URL |
| 404 | account not found |
| 422 | insufficient balance |
| 429 | rate limit exceeded (more than 5 redemption attempts in a burst, then >1 per 2s sustained) — same account only |

> Debit requests serialize per-account (pessimistic lock) rather than returning 409 on contention — a racing second request will simply wait, then likely fail with 422 once it sees the reduced balance.

---

## 4. Get Balance

`GET /accounts/{accountId}/balance`

**Response `200 OK`**
```json
{ "accountId": "b3f1e2a0-...", "balance": 20.00 }
```

Fast path — reads `Account.cachedBalance` directly, no replay.

---

## 5. Get Transaction History

`GET /accounts/{accountId}/entries`

**Response `200 OK`**
```json
[
  { "id": "...", "type": "CREDIT", "amount": 50.00, "referenceId": "bill-payment-98234", "createdAt": "..." },
  { "id": "...", "type": "DEBIT",  "amount": 30.00, "referenceId": "redemption-55123",  "createdAt": "..." }
]
```

---

## 6. Reverse an Entry (correction)

`POST /accounts/{accountId}/entries/{entryId}/reverse`

Posts a compensating entry against the original (never mutates or deletes it).

**Request**
```json
{
  "reason": "duplicate bill-payment webhook, correcting credit",
  "idempotencyKey": "reverse-e7c2-v1"
}
```

**Response `201 Created`** (compensating entry, opposite type of the original)
```json
{
  "id": "f9a1...",
  "accountId": "b3f1e2a0-...",
  "amount": 50.00,
  "type": "DEBIT",
  "status": "POSTED",
  "referenceId": "reversal-of-e7c2...",
  "createdAt": "2026-07-20T11:00:00Z"
}
```

**Errors**
| Status | Cause |
|---|---|
| 403 | token's account does not match `{accountId}` in the URL |
| 404 | account or entry not found |
| 409 | entry already reversed |
| 422 | reversing a CREDIT would leave balance negative (points already spent) |

---

## 7. Audit (balance replay)

`GET /accounts/{accountId}/audit`

Recomputes balance from the append-only log and compares to the cache — the correctness proof.

**Response `200 OK`**
```json
{
  "accountId": "b3f1e2a0-...",
  "cachedBalance": 20.00,
  "computedBalance": 20.00,
  "consistent": true
}
```

If `consistent: false`, it signals a bug in the credit/debit path (cache and log disagree) — this endpoint is the canary for that class of error.

---

## Error Response Shape (all 4xx/5xx)

```json
{
  "timestamp": "2026-07-20T10:05:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient balance: have 20.00, need 30.00"
}
```

---

## Design notes for interview discussion

- **Idempotency key is mandatory on every write**, not optional — this is deliberate. It forces callers (and you, in a demo) to think about retry-safety from day one rather than bolting it on later.
- **Why 200 vs 201 on idempotent replay**: a replayed request didn't create anything new, so `200` with the original resource is more correct than pretending a fresh `201` happened.
- **Why debit doesn't just return 409 on lock contention**: with a pessimistic lock, the second request isn't rejected — it queues briefly and then evaluates against the *post-first-request* balance, which is the more realistic "redeem" UX than making the user manually retry.
