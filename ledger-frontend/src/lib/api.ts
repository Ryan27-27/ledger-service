import type {
  AccountSummary,
  ApiError,
  AuditResponse,
  AuthResponse,
  BalanceResponse,
  LedgerEntryResponse,
  Meta,
  PageResponse,
} from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";
const ACCESS_KEY = "ledger.token";
const REFRESH_KEY = "ledger.refresh";

export class LedgerApiError extends Error {
  status: number;
  code: string | undefined;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
    this.name = "LedgerApiError";
  }
}

export function getToken(): string | null {
  return localStorage.getItem(ACCESS_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}

export function setTokens(access: string, refresh: string) {
  localStorage.setItem(ACCESS_KEY, access);
  localStorage.setItem(REFRESH_KEY, refresh);
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}

let sessionExpiredHandler: (() => void) | null = null;

/** Called when the access token is rejected and a refresh is impossible; the UI should return to login. */
export function setSessionExpiredHandler(handler: (() => void) | null) {
  sessionExpiredHandler = handler;
}

function send(path: string, options: RequestInit): Promise<Response> {
  const token = getToken();
  return fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers ?? {}),
    },
  });
}

// Single-flight: if several requests hit 401 at once they share ONE refresh call.
// Refresh tokens rotate (each is single-use), so concurrent refreshes would
// otherwise present the same token twice and look like token theft.
let refreshInFlight: Promise<boolean> | null = null;

function refreshTokens(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return Promise.resolve(false);

  refreshInFlight ??= (async () => {
    try {
      const res = await fetch(`${BASE_URL}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return false;
      const data: AuthResponse = await res.json();
      setTokens(data.token, data.refreshToken);
      return true;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  { retryOn401 = true }: { retryOn401?: boolean } = {},
): Promise<T> {
  let res = await send(path, options);

  if (res.status === 401 && retryOn401) {
    if (await refreshTokens()) {
      res = await send(path, options);
    }
    if (res.status === 401) {
      clearTokens();
      sessionExpiredHandler?.();
    }
  }

  if (!res.ok) {
    let message = `Request failed with status ${res.status}`;
    let code: string | undefined;
    try {
      const body: ApiError = await res.json();
      message = body.message ?? message;
      code = body.code;
    } catch {
      // response wasn't JSON, keep the generic message
    }
    throw new LedgerApiError(res.status, message, code);
  }

  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * One key per user ACTION, not per HTTP call: the modal creates it once when it
 * opens and reuses it for every retry of that action, so a double-click or a
 * retry after a timeout can never apply the same operation twice.
 */
export function newIdempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

const json = (body: unknown): RequestInit => ({ method: "POST", body: JSON.stringify(body) });

export const ledgerApi = {
  getMeta: () => request<Meta>("/meta", {}, { retryOn401: false }),

  register: (username: string, password: string) =>
    request<AuthResponse>("/auth/register", json({ username, password }), { retryOn401: false }),

  login: (username: string, password: string) =>
    request<AuthResponse>("/auth/login", json({ username, password }), { retryOn401: false }),

  logout: (refreshToken: string) =>
    request<void>("/auth/logout", json({ refreshToken }), { retryOn401: false }),

  getBalance: (accountId: string) => request<BalanceResponse>(`/accounts/${accountId}/balance`),

  getHistory: (accountId: string, page = 0, size = 20) =>
    request<PageResponse<LedgerEntryResponse>>(`/accounts/${accountId}/entries?page=${page}&size=${size}`),

  getAudit: (accountId: string) => request<AuditResponse>(`/accounts/${accountId}/audit`),

  credit: (accountId: string, amount: number, referenceId: string, idempotencyKey: string) =>
    request<LedgerEntryResponse>(
      `/accounts/${accountId}/credits`,
      json({ amount, referenceId, idempotencyKey }),
    ),

  debit: (accountId: string, amount: number, referenceId: string, idempotencyKey: string) =>
    request<LedgerEntryResponse>(
      `/accounts/${accountId}/debits`,
      json({ amount, referenceId, idempotencyKey }),
    ),

  reverse: (accountId: string, entryId: string, reason: string, idempotencyKey: string) =>
    request<LedgerEntryResponse>(
      `/accounts/${accountId}/entries/${entryId}/reverse`,
      json({ reason, idempotencyKey }),
    ),

  listAccounts: (q: string, page = 0, size = 8) =>
    request<PageResponse<AccountSummary>>(
      `/admin/accounts?q=${encodeURIComponent(q)}&page=${page}&size=${size}`,
    ),
};
