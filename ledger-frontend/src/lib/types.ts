export type EntryType = "CREDIT" | "DEBIT";
export type EntryStatus = "POSTED" | "REVERSED";

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  accountId: string;
  username: string;
}

export interface Account {
  id: string;
  userId: string;
  cachedBalance: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface LedgerEntryResponse {
  id: string;
  accountId: string;
  amount: number;
  type: EntryType;
  status: EntryStatus;
  referenceId: string;
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
  message: string;
}
