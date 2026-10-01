import { useState } from "react";
import { useLedger } from "./hooks/useLedger";
import { AuthScreen } from "./components/AuthScreen";
import { BalanceHero } from "./components/BalanceHero";
import { ActionBar } from "./components/ActionBar";
import { LedgerTable } from "./components/LedgerTable";
import { TransactionModal } from "./components/TransactionModal";
import { AdminPanel } from "./components/AdminPanel";
import { ErrorBanner } from "./components/ErrorBanner";
import type { EntryType } from "./lib/types";

export default function App() {
  const {
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
    viewAccount,
    stopViewing,
    clearError,
  } = useLedger();

  const [modalType, setModalType] = useState<EntryType | null>(null);
  const [showAccounts, setShowAccounts] = useState(false);

  if (!session) {
    return (
      <>
        {error && (
          <div className="fixed top-4 left-1/2 -translate-x-1/2 z-50 w-full max-w-sm px-6">
            <ErrorBanner message={error} onDismiss={clearError} />
          </div>
        )}
        <AuthScreen onLogin={login} onRegister={register} loading={loading} />
      </>
    );
  }

  const shownName = viewing?.username ?? session.username;

  return (
    <div className="min-h-screen px-5 py-10 sm:px-8">
      <div className="max-w-2xl mx-auto space-y-6">
        <BalanceHero
          username={shownName}
          role={viewing ? "USER" : session.role}
          balance={balance}
          audit={audit}
          onSignOut={signOut}
        />

        {viewing && (
          <div className="flex items-center justify-between bg-amber-bg border border-amber/30 rounded-lg px-4 py-2.5">
            <p className="text-sm text-amber">
              Viewing <span className="font-mono">{viewing.username}</span>'s ledger as admin
            </p>
            <button onClick={stopViewing} className="text-xs font-mono text-amber/80 hover:text-amber">
              back to mine
            </button>
          </div>
        )}

        {error && <ErrorBanner message={error} onDismiss={clearError} />}

        <ActionBar
          canCredit={canCredit}
          onCredit={() => setModalType("CREDIT")}
          onDebit={() => setModalType("DEBIT")}
        />

        {isAdmin && (
          <div>
            <button
              onClick={() => setShowAccounts((v) => !v)}
              className="text-xs font-mono text-text-muted hover:text-text-primary transition-colors mb-3"
            >
              {showAccounts ? "▾ hide accounts" : "▸ browse all accounts"}
            </button>
            {showAccounts && (
              <AdminPanel
                currentAccountId={viewing?.accountId ?? session.accountId}
                onSelect={(id, name) => viewAccount(id, name)}
              />
            )}
          </div>
        )}

        <div>
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-display text-sm font-600 text-text-muted uppercase tracking-wide">Ledger</h2>
            <span className="text-[11px] font-mono text-text-faint">
              {totalEntries} {totalEntries === 1 ? "entry" : "entries"} · append-only
            </span>
          </div>
          <LedgerTable
            entries={entries}
            canReverse={canReverse}
            hasMore={hasMore}
            loadingMore={loadingMore}
            onLoadMore={loadMore}
            onReverse={reverse}
          />
        </div>
      </div>

      {modalType && (
        <TransactionModal
          type={modalType}
          onClose={() => setModalType(null)}
          onSubmit={async (amount, referenceId, key) => {
            if (modalType === "CREDIT") {
              await credit(amount, referenceId, key);
            } else {
              await debit(amount, referenceId, key);
            }
          }}
        />
      )}
    </div>
  );
}
