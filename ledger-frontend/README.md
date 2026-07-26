# Ledger Frontend

A React + TypeScript + Tailwind client for the `ledger-service` Spring Boot API.

## What it does

- Open a points account
- Add points (credit) and redeem points (debit), each through a form that
  requires a reference ID — the UI generates a fresh idempotency key per
  action automatically (`src/lib/api.ts` → `newIdempotencyKey`)
- View the full transaction ledger with a running balance per row — newest
  entry on top, oldest at the bottom, exactly like a bank passbook
- Reverse any posted entry (with a reason) and watch the compensating entry
  appear as a new row rather than the original disappearing
- A live "ledger verified" badge calls `/audit` and shows green when the
  cached balance matches the replayed total from the append-only log, red
  if they ever disagree

## Setup

```bash
npm install
cp .env.example .env      # point VITE_API_BASE_URL at your backend if not localhost:8080
npm run dev                # http://localhost:5173
```

Requires `ledger-service` running on `localhost:8080` (see the backend
README — `docker compose up --build` there is the fastest path). CORS is
already configured on the backend to allow `localhost:5173`.

## Build

```bash
npm run build      # outputs to dist/
npm run preview    # serve the production build locally
```

## Structure

```
src/
  lib/
    types.ts     — TypeScript types mirroring the backend DTOs
    api.ts       — fetch wrapper + idempotency key generation
  hooks/
    useLedger.ts — all account/balance/entries/audit state + mutations
  components/
    AccountOnboarding.tsx  — create-account screen
    BalanceHero.tsx        — balance display + audit seal
    ActionBar.tsx           — credit/debit trigger buttons
    TransactionModal.tsx    — shared credit/debit form
    LedgerTable.tsx         — passbook-style entry list with running balance
    ErrorBanner.tsx
  App.tsx
```

## Design notes

Dark ink-navy palette (not pure black) with two functional accent colors —
teal for credits, coral for debits — mirroring how ledger data is read at a
glance: green in, red out. Amounts and IDs use a monospace face
(JetBrains Mono) throughout, since tabular alignment matters when you're
scanning a column of numbers for correctness — the same reason real bank
statements and terminal ledgers use fixed-width type.
