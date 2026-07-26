import { useState } from "react";
import { useLedger } from "./hooks/useLedger";
import { AuthScreen } from "./components/AuthScreen";
import { BalanceHero } from "./components/BalanceHero";
import { ActionBar } from "./components/ActionBar";
import { LedgerTable } from "./components/LedgerTable";
import { TransactionModal } from "./components/TransactionModal";
import { ErrorBanner } from "./components/ErrorBanner";
import type { EntryType } from "./lib/types";

export default function App() {
  const {
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
    clearError,
  } = useLedger();

  const [modalType, setModalType] = useState<EntryType | null>(null);

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

  return (
    <div className="min-h-screen px-5 py-10 sm:px-8">
      <div className="max-w-2xl mx-auto space-y-6">
        <BalanceHero
          userId={session.username}
          balance={balance}
          audit={audit}
          onSignOut={signOut}
        />

        {error && <ErrorBanner message={error} onDismiss={clearError} />}

        <ActionBar onCredit={() => setModalType("CREDIT")} onDebit={() => setModalType("DEBIT")} />

        <div>
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-display text-sm font-600 text-text-muted uppercase tracking-wide">
              Ledger
            </h2>
            <span className="text-[11px] font-mono text-text-faint">
              {entries.length} {entries.length === 1 ? "entry" : "entries"} · append-only
            </span>
          </div>
          <LedgerTable entries={entries} onReverse={reverse} />
        </div>
      </div>

      {modalType && (
        <TransactionModal
          type={modalType}
          onClose={() => setModalType(null)}
          onSubmit={async (amount, referenceId) => {
            if (modalType === "CREDIT") {
              await credit(amount, referenceId);
            } else {
              await debit(amount, referenceId);
            }
          }}
        />
      )}
    </div>
  );
}
