import { type FormEvent, useEffect, useState } from "react";
import { ledgerApi, LedgerApiError } from "../lib/api";
import type { AccountSummary, PageResponse } from "../lib/types";

interface Props {
  currentAccountId: string;
  onSelect: (accountId: string, username: string) => void;
}

/** Admin-only: search every account and open its ledger. */
export function AdminPanel({ currentAccountId, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<AccountSummary> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    ledgerApi
      .listAccounts(submitted, page)
      .then((data) => {
        if (!cancelled) {
          setResult(data);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof LedgerApiError ? e.message : "Couldn't load accounts");
      });
    return () => {
      cancelled = true;
    };
  }, [submitted, page]);

  const search = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setSubmitted(query.trim());
  };

  return (
    <section className="border border-ink-border rounded-xl overflow-hidden" aria-label="All accounts">
      <form onSubmit={search} className="flex gap-2 p-3 border-b border-ink-border bg-ink-raised">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search username…"
          aria-label="Search username"
          className="flex-1 bg-ink border border-ink-border rounded-lg px-3 py-2 text-sm font-mono
                     placeholder:text-text-faint focus:border-credit/50 transition-colors"
        />
        <button type="submit" className="text-xs font-mono text-text-muted hover:text-text-primary px-3">
          search
        </button>
      </form>

      {error && <p className="px-4 py-3 text-xs font-mono text-debit">{error}</p>}

      <ul className="divide-y divide-ink-border">
        {result?.items.map((a) => (
          <li key={a.accountId} className="flex items-center justify-between gap-3 px-4 py-2.5">
            <div className="min-w-0">
              <span className="text-sm font-mono truncate">{a.username}</span>
              {a.role === "ADMIN" && (
                <span className="ml-2 text-[10px] font-mono text-amber px-1.5 py-0.5 rounded bg-amber-bg">admin</span>
              )}
            </div>
            <div className="flex items-center gap-4">
              <span className="text-sm font-mono tabular text-text-muted">{a.balance.toFixed(2)}</span>
              <button
                onClick={() => onSelect(a.accountId, a.username)}
                disabled={a.accountId === currentAccountId}
                className="text-[11px] font-mono text-text-faint hover:text-credit transition-colors disabled:opacity-30"
              >
                {a.accountId === currentAccountId ? "viewing" : "open"}
              </button>
            </div>
          </li>
        ))}
        {result && result.items.length === 0 && (
          <li className="px-4 py-6 text-center text-sm text-text-muted">No accounts match.</li>
        )}
      </ul>

      {result && result.totalPages > 1 && (
        <div className="flex items-center justify-between px-4 py-2.5 border-t border-ink-border text-[11px] font-mono text-text-faint">
          <button onClick={() => setPage((p) => p - 1)} disabled={page === 0} className="disabled:opacity-30">
            ← prev
          </button>
          <span>
            {page + 1} / {result.totalPages}
          </span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={page + 1 >= result.totalPages}
            className="disabled:opacity-30"
          >
            next →
          </button>
        </div>
      )}
    </section>
  );
}
