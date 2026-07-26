import type {
  ApiError,
  AuditResponse,
  AuthResponse,
  BalanceResponse,
  LedgerEntryResponse,
} from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";
const TOKEN_KEY = "ledger.token";

export class LedgerApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "LedgerApiError";
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options?.headers ?? {}),
    },
  });

  if (!res.ok) {
    let message = `Request failed with status ${res.status}`;
    try {
      const body: ApiError = await res.json();
      message = body.message ?? message;
    } catch {
      // response wasn't JSON, keep the generic message
    }
    throw new LedgerApiError(res.status, message);
  }

  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Generates a fresh idempotency key per user action -- one click, one key. */
export function newIdempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

export const ledgerApi = {
  register: (username: string, password: string) =>
    request<AuthResponse>("/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  login: (username: string, password: string) =>
    request<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  getBalance: (accountId: string) =>
    request<BalanceResponse>(`/accounts/${accountId}/balance`),

  getHistory: (accountId: string) =>
    request<LedgerEntryResponse[]>(`/accounts/${accountId}/entries`),

  getAudit: (accountId: string) =>
    request<AuditResponse>(`/accounts/${accountId}/audit`),

  credit: (accountId: string, amount: number, referenceId: string) =>
    request<LedgerEntryResponse>(`/accounts/${accountId}/credits`, {
      method: "POST",
      body: JSON.stringify({
        amount,
        referenceId,
        idempotencyKey: newIdempotencyKey("credit"),
      }),
    }),

  debit: (accountId: string, amount: number, referenceId: string) =>
    request<LedgerEntryResponse>(`/accounts/${accountId}/debits`, {
      method: "POST",
      body: JSON.stringify({
        amount,
        referenceId,
        idempotencyKey: newIdempotencyKey("debit"),
      }),
    }),

  reverse: (accountId: string, entryId: string, reason: string) =>
    request<LedgerEntryResponse>(`/accounts/${accountId}/entries/${entryId}/reverse`, {
      method: "POST",
      body: JSON.stringify({
        reason,
        idempotencyKey: newIdempotencyKey("reverse"),
      }),
    }),
};
