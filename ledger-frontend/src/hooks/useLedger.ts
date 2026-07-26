import { useCallback, useEffect, useState } from "react";
import { clearToken, getToken, ledgerApi, LedgerApiError, setToken } from "../lib/api";
import type { AuditResponse, LedgerEntryResponse } from "../lib/types";

interface Session {
  accountId: string;
  username: string;
}

const SESSION_KEY = "ledger.session";

export function useLedger() {
  const [session, setSession] = useState<Session | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [entries, setEntries] = useState<LedgerEntryResponse[]>([]);
  const [audit, setAudit] = useState<AuditResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async (accountId: string) => {
    setError(null);
    try {
      const [balanceRes, entriesRes, auditRes] = await Promise.all([
        ledgerApi.getBalance(accountId),
        ledgerApi.getHistory(accountId),
        ledgerApi.getAudit(accountId),
      ]);
      setBalance(balanceRes.balance);
      setEntries(entriesRes); // ascending order (oldest first), as returned by the API
      setAudit(auditRes);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Couldn't reach the ledger service");
    }
  }, []);

  // Restore a previously authenticated session on load. If the stored token
  // has expired, the first refresh() call will fail with 401 and signOut()
  // below sends the user back to the login screen.
  useEffect(() => {
    const raw = localStorage.getItem(SESSION_KEY);
    const token = getToken();
    if (raw && token) {
      const restored: Session = JSON.parse(raw);
      setSession(restored);
      refresh(restored.accountId);
    }
  }, [refresh]);

  const applyAuthResult = useCallback(
    async (result: { token: string; accountId: string; username: string }) => {
      setToken(result.token);
      const newSession = { accountId: result.accountId, username: result.username };
      localStorage.setItem(SESSION_KEY, JSON.stringify(newSession));
      setSession(newSession);
      await refresh(result.accountId);
    },
    [refresh],
  );

  const register = useCallback(async (username: string, password: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await ledgerApi.register(username, password);
      await applyAuthResult(result);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Couldn't create account");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [applyAuthResult]);

  const login = useCallback(async (username: string, password: string) => {
    setLoading(true);
    setError(null);
    try {
      const result = await ledgerApi.login(username, password);
      await applyAuthResult(result);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Couldn't sign in");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [applyAuthResult]);

  const credit = useCallback(async (amount: number, referenceId: string) => {
    if (!session) return;
    setLoading(true);
    setError(null);
    try {
      await ledgerApi.credit(session.accountId, amount, referenceId);
      await refresh(session.accountId);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Credit failed");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [session, refresh]);

  const debit = useCallback(async (amount: number, referenceId: string) => {
    if (!session) return;
    setLoading(true);
    setError(null);
    try {
      await ledgerApi.debit(session.accountId, amount, referenceId);
      await refresh(session.accountId);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Debit failed");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [session, refresh]);

  const reverse = useCallback(async (entryId: string, reason: string) => {
    if (!session) return;
    setLoading(true);
    setError(null);
    try {
      await ledgerApi.reverse(session.accountId, entryId, reason);
      await refresh(session.accountId);
    } catch (e) {
      setError(e instanceof LedgerApiError ? e.message : "Reversal failed");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [session, refresh]);

  const signOut = useCallback(() => {
    clearToken();
    localStorage.removeItem(SESSION_KEY);
    setSession(null);
    setBalance(null);
    setEntries([]);
    setAudit(null);
  }, []);

  return {
    session,
    balance,
    entries,
    audit,
    loading,
    error,
    register,
    login,
    credit,
    debit,
    reverse,
    signOut,
    clearError: () => setError(null),
  };
}
