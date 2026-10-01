import type { AuditResponse, Role } from "../lib/types";

interface Props {
  username: string;
  role: Role;
  balance: number | null;
  audit: AuditResponse | null;
  onSignOut: () => void;
}

export function BalanceHero({ username, role, balance, audit, onSignOut }: Props) {
  return (
    <div className="flex items-start justify-between">
      <div>
        <div className="flex items-center gap-2 mb-2">
          <span className="h-1.5 w-1.5 rounded-full bg-credit" />
          <span className="font-mono text-xs text-text-muted tracking-widest uppercase">{username}</span>
          {role === "ADMIN" && (
            <span className="text-[10px] font-mono text-amber px-1.5 py-0.5 rounded bg-amber-bg uppercase">admin</span>
          )}
        </div>
        <div className="font-display font-600 text-5xl sm:text-6xl tracking-tight tabular">
          {balance === null ? (
            <span className="text-text-faint">— · —</span>
          ) : (
            <>
              {balance.toLocaleString(undefined, { minimumFractionDigits: 2 })}
              <span className="text-text-muted text-2xl ml-2 font-body font-normal">pts</span>
            </>
          )}
        </div>
      </div>

      <div className="flex flex-col items-end gap-2">
        <AuditSeal audit={audit} />
        <button
          onClick={onSignOut}
          className="text-xs font-mono text-text-faint hover:text-text-muted transition-colors"
        >
          sign out
        </button>
      </div>
    </div>
  );
}

function AuditSeal({ audit }: { audit: AuditResponse | null }) {
  if (!audit) {
    return (
      <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full border border-ink-border">
        <span className="h-1.5 w-1.5 rounded-full bg-text-faint animate-pulse" />
        <span className="text-[11px] font-mono text-text-faint">auditing…</span>
      </div>
    );
  }

  const ok = audit.consistent;
  return (
    <div
      title={`cached ${audit.cachedBalance} · replayed ${audit.computedBalance}`}
      className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full border ${
        ok ? "border-credit/30 bg-credit-bg" : "border-debit/30 bg-debit-bg"
      }`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${ok ? "bg-credit" : "bg-debit"}`} />
      <span className={`text-[11px] font-mono ${ok ? "text-credit" : "text-debit"}`}>
        {ok ? "ledger verified" : "balance mismatch"}
      </span>
    </div>
  );
}
