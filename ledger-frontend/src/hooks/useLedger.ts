import { useCallback, useEffect, useRef, useState } from "react";
import {
  clearTokens,
  getRefreshToken,
  getToken,
  ledgerApi,
  LedgerApiError,
  setSessionExpiredHandler,
  setTokens,
} from "../lib/api";
import type { AuditResponse, AuthResponse, LedgerEntryResponse, Meta, Role } from "../lib/types";

export interface Session {
  accountId: string;
  username: string;
  role: Role;
}

/** The account currently on screen when an admin is looking at someone else's ledger. */
export interface Viewing {
  accountId: string;
  username: string;
}

const SESSION_KEY = "ledger.session";
const PAGE_SIZE = 20;

function readStoredSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw || !getToken()) return null;
    const parsed = JSON.parse(raw) as Partial<Session>;
    if (!parsed.accountId || !parsed.username || !parsed.role) return null;
    return parsed as Session;
  } catch {
    return null;
  }
}

const messageOf = (e: unknown, fallback: string) =>
  e instanceof LedgerApiError ? e.message : fallback;

export function useLedger() {
  const [session, setSession] = useState<Session | null>(readStoredSession);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [viewing, setViewing] = useState<Viewing | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [entries, setEntries] = useState<LedgerEntryResponse[]>([]);
  const [page, setPage] = useState(0);
  const [totalEntries, setTotalEntries] = useState(0);
  const [audit, setAudit] = useState<AuditResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Ignores results of loads that were superseded (e.g. admin switched account mid-request).
  const loadSeq = useRef(0);

  const targetId = viewing?.accountId ?? session?.accountId ?? null;
  const isAdmin = session?.role === "ADMIN";
  const demoMode = meta?.demoMode === true;
  const canCredit = isAdmin || demoMode;
  const canReverse = isAdmin || demoMode;
  const hasMore = entries.length < totalEntries;

  const resetView = useCallback(() => {
    setBalance(null);
    setEntries([]);
    setPage(0);
    setTotalEntries(0);
    setAudit(null);
  }, []);

  const refresh = useCallback(async (accountId: string) => {
    const seq = ++loadSeq.current;
    setError(null);
    try {
      const [balanceRes, historyRes, auditRes] = await Promise.all([
        ledgerApi.getBalance(accountId),
        ledgerApi.getHistory(accountId, 0, PAGE_SIZE),
        ledgerApi.getAudit(accountId),
      ]);
      if (seq !== loadSeq.current) return;
      setBalance(balanceRes.balance);
      setEntries(historyRes.items); // newest first, running balance computed server-side
      setPage(0);
      setTotalEntries(historyRes.totalElements);
      setAudit(auditRes);
    } catch (e) {
      if (seq === loadSeq.current) setError(messageOf(e, "Couldn't reach the ledger service"));
    }
  }, []);

  const loadMore = useCallback(async () => {
    if (!targetId || loadingMore) return;
    setLoadingMore(true);
    try {
      const next = await ledgerApi.getHistory(targetId, page + 1, PAGE_SIZE);
      setEntries((prev) => {
        const seen = new Set(prev.map((e) => e.id));
        return [...prev, ...next.items.filter((e) => !seen.has(e.id))];
      });
      setPage(next.page);
      setTotalEntries(next.totalElements);
    } catch (e) {
      setError(messageOf(e, "Couldn't load more entries"));
    } finally {
      setLoadingMore(false);
    }
  }, [targetId, loadingMore, page]);

  // Public runtime info (e.g. demo mode) -- needed before login to know what the UI may offer.
  useEffect(() => {
    ledgerApi.getMeta().then(setMeta).catch(() => setMeta(null));
  }, []);

  // Load (and reload on account switch) whatever account is on screen.
  useEffect(() => {
    if (targetId) void refresh(targetId);
  }, [targetId, refresh]);

  const signOut = useCallback(() => {
    const refreshToken = getRefreshToken();
    if (refreshToken) void ledgerApi.logout(refreshToken).catch(() => undefined); // best effort
    clearTokens();
    localStorage.removeItem(SESSION_KEY);
    loadSeq.current++;
    setSession(null);
    setViewing(null);
    resetView();
  }, [resetView]);

  // The API client calls this when a 401 can't be recovered by refreshing.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      localStorage.removeItem(SESSION_KEY);
      loadSeq.current++;
      setSession(null);
      setViewing(null);
      resetView();
      setError("Your session expired. Please sign in again.");
    });
    return () => setSessionExpiredHandler(null);
  }, [resetView]);

  const applyAuthResult = useCallback((result: AuthResponse) => {
    setTokens(result.token, result.refreshToken);
    const next: Session = { accountId: result.accountId, username: result.username, role: result.role };
    localStorage.setItem(SESSION_KEY, JSON.stringify(next));
    setViewing(null);
    setSession(next); // the targetId effect loads the ledger
  }, []);

  const authenticate = useCallback(
    async (call: () => Promise<AuthResponse>, fallback: string) => {
      setLoading(true);
      setError(null);
      try {
        applyAuthResult(await call());
      } catch (e) {
        setError(messageOf(e, fallback));
        throw e;
      } finally {
        setLoading(false);
      }
    },
    [applyAuthResult],
  );

  const register = useCallback(
    (username: string, password: string) =>
      authenticate(() => ledgerApi.register(username, password), "Couldn't create account"),
    [authenticate],
  );

  const login = useCallback(
    (username: string, password: string) =>
      authenticate(() => ledgerApi.login(username, password), "Couldn't sign in"),
    [authenticate],
  );

  const mutate = useCallback(
    async (action: (accountId: string) => Promise<unknown>, fallback: string) => {
      if (!targetId) return;
      setLoading(true);
      setError(null);
      try {
        await action(targetId);
        await refresh(targetId);
      } catch (e) {
        setError(messageOf(e, fallback));
        throw e;
      } finally {
        setLoading(false);
      }
    },
    [targetId, refresh],
  );

  const credit = useCallback(
    (amount: number, referenceId: string, key: string) =>
      mutate((id) => ledgerApi.credit(id, amount, referenceId, key), "Credit failed"),
    [mutate],
  );

  const debit = useCallback(
    (amount: number, referenceId: string, key: string) =>
      mutate((id) => ledgerApi.debit(id, amount, referenceId, key), "Redemption failed"),
    [mutate],
  );

  const reverse = useCallback(
    (entryId: string, reason: string, key: string) =>
      mutate((id) => ledgerApi.reverse(id, entryId, reason, key), "Reversal failed"),
    [mutate],
  );

  return {
    session,
    viewing,
    balance,
    entries,
    totalEntries,
    hasMore,
    audit,
    loading,
    loadingMore,
    error,
    isAdmin,
    canCredit,
    canReverse,
    register,
    login,
    credit,
    debit,
    reverse,
    loadMore,
    signOut,
    viewAccount: (accountId: string, username: string) =>
      setViewing(accountId === session?.accountId ? null : { accountId, username }),
    stopViewing: () => setViewing(null),
    clearError: () => setError(null),
  };
}
