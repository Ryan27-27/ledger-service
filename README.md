# Fullstack Ledger

An **append-only points ledger** with a React UI — the kind of core a rewards, wallet or
loyalty platform sits on. The interesting part isn't the CRUD; it's making money-like state
_correct_ under retries, concurrency and mistakes.

```
React 19 + TypeScript + Tailwind  ──►  Spring Boot 3 (Java 21)  ──►  PostgreSQL 16 (source of truth)
          (nginx in Docker)                  │
                                             └────────────────────►  Redis 7 (token-bucket rate limiting)
```

## Run it

```bash
docker compose up --build
```

|                  |                                                                         |
| ---------------- | ----------------------------------------------------------------------- |
| UI               | http://localhost:3000                                                   |
| API + Swagger UI | http://localhost:8080/swagger-ui.html                                   |
| Admin login      | `admin` / `admin-password-123` (override in `.env`, see `.env.example`) |

Register a user, add points, redeem, reverse an entry, and watch the **"ledger verified"** badge
— it comes from `/audit`, which replays the whole log and compares it with the cached balance.
Log in as `admin` to browse every account and act on them.

Without Docker: see [ledger-service/README.md](ledger-service/README.md) and
[ledger-frontend/README.md](ledger-frontend/README.md).

## What it demonstrates

**Correctness**

- **Append-only log.** Rows are never edited or deleted; mistakes are fixed by a _compensating entry_
  linked to the original (`reversal_of`). This is enforced **in the database** with a trigger — only
  `status` may change (`POSTED → REVERSED`, never back) — so a bug or manual SQL can't rewrite history.
- **Audit replay.** `balance = Σ credits − Σ debits` over the log, compared to the cached balance on
  every page load. The cache is a performance optimisation, never the truth.
- **No double spend.** Debits take a pessimistic row lock (`SELECT … FOR UPDATE`); credits use optimistic
  locking (`@Version`) with bounded retry. Defence in depth: a DB `CHECK (cached_balance >= 0)`.
- **Idempotency.** Every write carries a client key, unique _per account_. Same key + same request →
  `200` with the original entry; same key + different request → `409`; a race between two identical
  requests is resolved by the unique constraint, not by luck. The UI generates one key per _user action_
  and reuses it on retry.
- **Reversal rules.** An entry can be reversed once (partial unique index), a reversal can't be reversed,
  and reversing a credit re-checks the balance because the points may already be spent.

**Security**

- JWT access tokens (15 min) + **rotating refresh tokens** (opaque, stored only as SHA-256 hashes;
  replaying a used token revokes the user's whole session family).
- Ownership checks on every account route; **credit and reverse are admin-only** (they create/undo points).
  `APP_DEMO_MODE=true` relaxes this to "own account only" so the UI works without an admin.
- bcrypt, constant-time-ish login (dummy hash for unknown users), password length bounded to bcrypt's 72 bytes,
  per-IP rate limiting on `/auth/**`, per-account rate limiting on redemptions (Redis Lua token bucket,
  fail-open if Redis is down — the financial invariants live in Postgres, not in the limiter).
- Non-root containers, security headers, CORS allow-list from config, secrets from env (the app refuses
  a JWT secret under 32 bytes).

**Engineering practice**

- One error contract for _everything_ (`code`, `message`, `requestId`, `fieldErrors`) — controllers, security
  filters and framework errors alike. See [API_SPEC.md](ledger-service/API_SPEC.md).
- Request-id correlation across response headers and every log line; access log; Prometheus metrics
  (`ledger_entries_posted_total{type}`) and health probes; graceful shutdown.
- Flyway-owned schema, `ddl-auto: validate`. OpenAPI/Swagger generated from code.
- Server-side pagination with a **running balance computed in SQL** (window function), so the UI scales past
  the first page without recomputing anything client-side.
- Tests at each level (below) and a CI pipeline that also boots the full Docker stack.

## Architecture notes

```mermaid
erDiagram
    app_users ||--|| accounts : owns
    accounts ||--o{ ledger_entries : "has (append-only)"
    ledger_entries ||--o| ledger_entries : "reversal_of"
    app_users ||--o{ refresh_tokens : holds
    accounts { uuid id PK; numeric cached_balance "cache only"; bigint version }
    ledger_entries { uuid id PK; uuid account_id FK; numeric amount; string type "CREDIT|DEBIT"; string status "POSTED|REVERSED"; string idempotency_key "unique per account"; uuid reversal_of "unique, nullable" }
```

Request flow for a redemption: `JWT filter → ownership check → rate limiter → LedgerService.debit`
(`SELECT … FOR UPDATE` on the account → idempotency check → balance check → insert entry → update cache,
one transaction). Metrics are recorded only after commit.

Key trade-offs, deliberately made:

- **Cached balance + log** instead of computing balance on every read: O(1) reads, with the audit endpoint as the
  safety net. The cost is two writes per entry, kept consistent by the transaction and the row lock.
- **Mixed locking.** Debits are the contended path (double taps, retries) so they serialise on a row lock;
  credits are rarer and independent, so optimistic locking avoids holding locks for no reason.
- **Status flip on the original** (instead of a fully immutable row + a separate "reversed" table). It is the only
  mutable column, it's guarded by the trigger, and it makes "what can still be reversed?" a trivial query.

## Tests

| Layer                                              | What                                                                                                                                                                                                                                                                              | Needs Docker               |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| Backend unit                                       | JWT (expiry, forgery, tampered payload, short secret), token-bucket limiter incl. 100-thread contention                                                                                                                                                                           | no                         |
| Backend integration (Testcontainers + Postgres 16) | Reversals both directions & audit consistency, reverse-once rules, idempotency semantics, concurrent debits (exactly one wins), concurrent credits (no lost update), HTTP auth/ownership/admin rules, validation, pagination, refresh-token rotation + theft detection, demo mode | yes (auto-skipped without) |
| Frontend (Vitest + Testing Library)                | API client (auth header, error mapping, refresh-on-401, single-flight refresh, session expiry), modals (validation, **same idempotency key on retry**), ledger table (running balance, reversal eligibility, paging)                                                              | no                         |

```bash
cd ledger-service && mvn verify          # backend
cd ledger-frontend && npm ci && npm run lint && npm test && npm run build
```

## Known limitations / what I'd do next

- **Single currency, single account per user**, and no multi-account transfers (those would need two-sided
  double-entry postings).
- Access tokens are stateless, so a role change or revocation takes effect when the 15-minute token expires.
- Refresh tokens live in `localStorage` (simple, but exposed to XSS); the production-grade option is an
  `HttpOnly` `SameSite` cookie. Using one refresh token from two tabs at once trips theft detection and logs
  both out — a known trade-off of strict rotation.
- No expired-refresh-token cleanup job, no email verification / password reset, no account lockout beyond rate limiting.
- The in-memory rate limiter (profile `no-redis`) never evicts idle buckets; it exists for local dev and tests.
- Not load-tested: I'd add a k6 scenario hammering one account to put numbers behind the "no double spend" claim.
- Swagger UI is on by default for convenience; set `APP_SWAGGER_ENABLED=false` in production.
