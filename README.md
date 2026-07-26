# Ledger Service

An append-only rewards/points ledger service, modeled on how CRED's points
system likely works under the hood (pay a bill -> earn points -> redeem
points -> never double-spend, always auditable).

## Why this design

- **Append-only ledger**: `ledger_entries` rows are never updated or deleted.
  Corrections are made by inserting compensating entries. This gives a full
  audit trail and makes the system replayable/recoverable from any point.
- **Idempotency keys**: every write carries a client-supplied key with a
  unique DB constraint. A retried request (client timeout, network blip,
  at-least-once queue delivery) can never be double-counted -- the second
  insert fails fast, and the API returns the original result instead of an
  error.
- **Two concurrency strategies, used deliberately**:
  - `credit()` uses **optimistic locking** (`@Version` on `Account`) --
    credits come from many independent, low-contention sources (bill
    payments across the user base), so occasional retries are cheap.
  - `debit()` uses **pessimistic row locking** (`SELECT ... FOR UPDATE`) --
    redemptions on the *same* account are the actual contention point (e.g.
    a user double-tapping "redeem"), so we serialize at the DB level and
    make double-spend structurally impossible rather than statistically
    unlikely.
- **Audit endpoint**: `/audit` recomputes the balance by replaying every
  POSTED entry and compares it to the cached balance on `Account`. This is
  the concrete answer to "how do you guarantee correctness" -- the cache is
  just a performance optimization; the log is the source of truth.

- **JWT authentication, tied to resource ownership**: `POST /auth/register`
  provisions a `User` and `Account` together and returns a signed JWT with
  `accountId` embedded as a claim. Every ledger endpoint checks that the
  `accountId` in the URL matches the `accountId` in the caller's token (or
  that the caller has the `ADMIN` role) -- a valid token proves who you are,
  this check proves you're allowed to touch *this* account. See
  `SecurityConfig`'s Javadoc for exactly what changes if you swap this for
  OAuth2 against a real IdP (Auth0/Okta/Cognito) instead.
- **Distributed rate limiting via Redis**: `RedisTokenBucketRateLimiter` runs
  the entire read-refill-check-decrement sequence as one atomic Lua script
  inside Redis (`src/main/resources/scripts/token_bucket.lua`), so the limit
  holds correctly no matter how many instances of this service are running
  behind a load balancer. The original in-memory version is kept as a
  `no-redis` profile fallback for local dev without Redis running, wired
  through a shared `RateLimiter` interface so the controller doesn't know or
  care which one is active.
- **Reversals, not deletes**: `POST /entries/{id}/reverse` never touches the
  original row. It posts a compensating entry (opposite type, same amount),
  flips the original's status to `REVERSED`, and reuses the same
  credit/debit locking + idempotency machinery. This is the pattern real
  ledgers (and CRED's rewards system) use for chargebacks/corrections --
  the log only ever grows.

---

# System Design (LLD)

This project follows a layered architecture where each layer has a single responsibility.

```text
                    React (Vite)
                         │
                  HTTP REST + JWT
                         │
                         ▼
              Spring Security Filter
                         │
                 JWT Authentication
                         │
                         ▼
                 Controller Layer
        ┌──────────┬──────────────┬──────────────┐
        │          │              │
 AuthController AccountController LedgerController
        │          │              │
        └──────────┴──────────────┘
                    │
                    ▼
                Service Layer
       ┌─────────────────────────────┐
       │ AuthService                 │
       │ LedgerService               │
       │ AuditService                │
       └─────────────────────────────┘
                    │
                    ▼
             Repository Layer
       ┌─────────────────────────────┐
       │ UserRepository              │
       │ AccountRepository           │
       │ LedgerEntryRepository       │
       └─────────────────────────────┘
            │                 │
            ▼                 ▼
      PostgreSQL           Redis
```

## Request Lifecycle

Every incoming request follows the same high-level flow.

```text
Client
   │
   ▼
JWT Authentication
   │
   ▼
Authorization Check
   │
   ▼
Controller
   │
   ▼
Service
   │
   ▼
Repository
   │
   ▼
PostgreSQL / Redis
   │
   ▼
HTTP Response
```

---

# Credit Flow

Credits are low-contention operations, so optimistic locking is used.

```text
Client
   │
POST /credit
   │
   ▼
LedgerController
   │
   ▼
LedgerService.credit()
   │
   ├── Validate JWT
   ├── Validate Account Ownership
   ├── Check Idempotency Key
   ├── Load Account
   ├── Create Ledger Entry
   ├── Update Cached Balance
   └── Commit Transaction
   │
   ▼
Success Response
```

**Concurrency Strategy**

- Optimistic Locking (`@Version`)
- Suitable for many independent credit operations
- Retries are inexpensive

---

# Debit Flow

Debit operations require stronger consistency to prevent double spending.

```text
Client
   │
POST /debit
   │
   ▼
LedgerController
   │
   ▼
LedgerService.debit()
   │
   ├── Validate JWT
   ├── Validate Ownership
   ├── Check Idempotency
   ├── SELECT ... FOR UPDATE
   ├── Verify Balance
   ├── Insert Ledger Entry
   ├── Update Cached Balance
   └── Commit Transaction
   │
   ▼
Success Response
```

**Concurrency Strategy**

- Pessimistic Row Locking
- Prevents concurrent debits on the same account
- Eliminates double spending

---

# Reversal Flow

Ledger entries are never deleted.

Instead, compensating entries are created.

```text
Original Transaction
        │
        ▼
Marked REVERSED
        │
        ▼
Compensating Ledger Entry
        │
        ▼
Balance Updated
```

Example

```text
+500 Points
-500 Points
```

instead of deleting the original record.

This guarantees a complete audit trail.

---

# Authentication Flow

```text
Register/Login
      │
      ▼
AuthController
      │
      ▼
AuthService
      │
      ├── Verify Password
      ├── Generate JWT
      └── Return Token
```

Every subsequent request

```text
Client
   │
Bearer Token
   │
   ▼
Spring Security
   │
Validate JWT
   │
Extract accountId
   │
Authorize Request
   │
Controller
```

---

# Distributed Rate Limiting

```text
Incoming Request
        │
        ▼
Rate Limiter Filter
        │
        ▼
Redis Lua Script
        │
  ┌─────┴─────┐
  │           │
Allowed    Rate Limited
  │           │
  ▼           ▼
Continue   HTTP 429
```

Using a Redis Lua script guarantees that token refill and consumption occur atomically across multiple application instances.

---

# Audit Flow

The cached account balance is treated as an optimization.

The ledger remains the source of truth.

```text
Audit Request
      │
      ▼
Load Cached Balance
      │
      ▼
Replay Ledger Entries
      │
      ▼
Calculate Actual Balance
      │
      ▼
Compare Values
      │
 ┌────┴────┐
 │         │
Match   Mismatch
 │         │
Healthy   Investigation Required
```

---

# Entity Relationships

```text
User
 │
 │ 1
 ▼
Account
 │
 │ 1
 ▼
LedgerEntry
```

---

# Design Decisions

| Decision | Reason |
|-----------|--------|
| Append-only Ledger | Complete audit history and replayability |
| JWT Authentication | Stateless authentication |
| Account Ownership Validation | Prevent cross-account access |
| Idempotency Keys | Safe retries without duplicate transactions |
| Optimistic Locking | Efficient handling of concurrent credit operations |
| Pessimistic Locking | Prevent double spending during debits |
| Redis Token Bucket | Distributed rate limiting |
| Cached Balance | Fast reads while preserving ledger as the source of truth |
| Compensating Entries | Corrections without deleting historical records |

---


## Running locally

**Option A — everything in Docker:**
```bash
docker compose up --build     # builds the app image, starts Postgres + Redis + app
```

**Option B — Postgres + Redis in Docker, app on host (faster iteration):**
```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

**Option C — no Redis at all (in-memory rate limiter):**
```bash
docker compose up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=no-redis
```

## Authentication

```bash
# Register (creates User + Account, returns a JWT)
curl -X POST localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"aryan123","password":"correcthorsebattery"}'

# Use the token on any ledger endpoint
curl localhost:8080/api/v1/accounts/<accountId>/balance \
  -H "Authorization: Bearer <token>"
```

## Running tests

```bash
mvn test
```

- `LedgerConcurrencyTest` spins up a real Postgres via Testcontainers and
  fires 10 concurrent debit requests against an account that can only
  afford one -- asserts exactly one succeeds and the final balance is never
  negative or inconsistent with the replayed ledger.
- `RedisRateLimiterTest` spins up a real Redis via Testcontainers and fires
  50 concurrent requests at a bucket with capacity 5 -- asserts exactly 5
  are allowed, proving the Lua script is atomic under real concurrency
  rather than just "usually correct."

## API

See `API_SPEC.md` for the full endpoint reference.

## Roadmap (not yet built)

- OAuth2 resource-server mode (validate tokens from a real IdP instead of
  self-issuing them) -- see the Javadoc on `SecurityConfig` for the swap
- k6/JMeter load test results documented here with before/after latency
- Refresh tokens (current JWTs expire in 1 hour with no renewal path)
