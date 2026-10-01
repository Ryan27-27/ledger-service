# ledger-frontend

React 19 + TypeScript + Vite + Tailwind UI for the ledger service. See the [root README](../README.md)
for the overview; this file is the frontend quick start.

```bash
npm install
cp .env.example .env     # VITE_API_BASE_URL, defaults to http://localhost:8080/api/v1
npm run dev              # http://localhost:5173  (backend must allow this origin; the default does)
npm run lint && npm test && npm run build
```

## Structure

```
src/
  lib/api.ts          typed fetch client: bearer auth, refresh-on-401 (single-flight), error mapping
  lib/types.ts        mirrors the API's DTOs
  hooks/useLedger.ts  session, paging, admin "view account", mutations
  components/         AuthScreen, BalanceHero, ActionBar, LedgerTable, TransactionModal,
                      ReverseModal, AdminPanel, ErrorBanner
  test/setup.ts       Testing Library / jest-dom wiring
```

## Behaviours worth knowing

- **Idempotency keys are per user action.** A modal creates its key once and re-sends it if the user retries
  after a failure, so a timed-out request that *did* land is never applied twice.
- **Running balance comes from the server**, so it stays correct on any page of history.
- **Roles/permissions:** "Add points" and "reverse" appear only for admins (or for everyone when the
  backend reports `demoMode`). The server enforces the same rules; the UI just doesn't offer what would be refused.
- Expired access tokens are refreshed transparently; if the refresh fails the user is returned to sign-in.

## Docker

`Dockerfile` builds the app and serves it with unprivileged nginx, which also reverse-proxies `/api/` to the
backend (so the browser sees one origin and no CORS is needed). Use the root `docker-compose.yml`.
