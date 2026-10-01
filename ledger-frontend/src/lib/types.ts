export type EntryType = "CREDIT" | "DEBIT";
export type EntryStatus = "POSTED" | "REVERSED";
export type Role = "USER" | "ADMIN";

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  refreshToken: string;
  accountId: string;
  username: string;
  role: Role;
}

export interface Meta {
  demoMode: boolean;
  version: string;
}

export interface LedgerEntryResponse {
  id: string;
  accountId: string;
  amount: number;
  type: EntryType;
  status: EntryStatus;
  referenceId: string;
  remarks: string | null;
  /** Set on compensating entries: the id of the entry this one reverses. */
  reversalOf: string | null;
  createdAt: string;
  /** Account balance immediately after this entry (history endpoint only). */
  runningBalance: number | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AccountSummary {
  accountId: string;
  username: string;
  role: Role;
  balance: number;
  createdAt: string;
}

export interface BalanceResponse {
  accountId: string;
  balance: number;
}

export interface AuditResponse {
  accountId: string;
  cachedBalance: number;
  computedBalance: number;
  consistent: boolean;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  code?: string;
  message: string;
  requestId?: string;
}
