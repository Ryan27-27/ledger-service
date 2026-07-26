import { useMemo, useState } from "react";
import type { LedgerEntryResponse } from "../lib/types";

interface Props {
  entries: LedgerEntryResponse[]; // ascending, oldest first
  onReverse: (entryId: string, reason: string) => Promise<void>;
}

interface RowData extends LedgerEntryResponse {
  runningBalance: number;
}

export function LedgerTable({ entries, onReverse }: Props) {
  const [reversingId, setReversingId] = useState<string | null>(null);

  const rows: RowData[] = useMemo(() => {
    let running = 0;
    const withBalance = entries.map((e) => {
      if (e.status === "POSTED") {
        running += e.type === "CREDIT" ? e.amount : -e.amount;
      }
      return { ...e, runningBalance: running };
    });
    return withBalance.reverse(); // newest first for display
  }, [entries]);

  if (rows.length === 0) {
    return (
      <div className="border border-dashed border-ink-border rounded-xl py-14 text-center">
        <p className="text-sm text-text-muted">No entries yet.</p>
        <p className="text-xs text-text-faint mt-1 font-mono">
          the ledger fills in as points move
        </p>
      </div>
    );
  }

  return (
    <div className="border border-ink-border rounded-xl overflow-hidden">
      <div className="grid grid-cols-[1fr,auto,auto,auto] gap-3 px-4 py-2.5 border-b border-ink-border bg-ink-raised">
        <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide">Entry</span>
        <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide text-right">Amount</span>
        <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide text-right hidden sm:block">
          Balance
        </span>
        <span className="w-16" />
      </div>

      <div className="divide-y divide-ink-border">
        {rows.map((entry) => (
          <Row
            key={entry.id}
            entry={entry}
            reversing={reversingId === entry.id}
            onReverseClick={async () => {
              const reason = window.prompt("Reason for reversal?");
              if (reason === null) return;
              setReversingId(entry.id);
              try {
                await onReverse(entry.id, reason || "manual correction");
              } finally {
                setReversingId(null);
              }
            }}
          />
        ))}
      </div>
    </div>
  );
}

function Row({
  entry,
  reversing,
  onReverseClick,
}: {
  entry: RowData;
  reversing: boolean;
  onReverseClick: () => void;
}) {
  const isReversed = entry.status === "REVERSED";
  const isCredit = entry.type === "CREDIT";

  return (
    <div
      className={`grid grid-cols-[1fr,auto,auto,auto] gap-3 px-4 py-3 items-center transition-colors
        ${isReversed ? "opacity-45" : "hover:bg-ink-raised/40"}`}
    >
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span
            className={`text-[10px] font-mono px-1.5 py-0.5 rounded uppercase tracking-wide ${
              isCredit ? "bg-credit-bg text-credit" : "bg-debit-bg text-debit"
            }`}
          >
            {entry.type}
          </span>
          {isReversed && (
            <span className="text-[10px] font-mono text-amber px-1.5 py-0.5 rounded bg-amber-bg">
              ↺ reversed
            </span>
          )}
        </div>
        <p className="text-sm text-text-primary mt-1 truncate" title={entry.referenceId}>
          {entry.referenceId}
        </p>
        <p className="text-[11px] font-mono text-text-faint mt-0.5">
          {new Date(entry.createdAt).toLocaleString(undefined, {
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
          })}
        </p>
      </div>

      <span
        className={`text-sm font-mono tabular text-right whitespace-nowrap ${
          isReversed ? "line-through text-text-faint" : isCredit ? "text-credit" : "text-debit"
        }`}
      >
        {isCredit ? "+" : "–"}
        {entry.amount.toFixed(2)}
      </span>

      <span className="text-sm font-mono tabular text-right text-text-muted hidden sm:block whitespace-nowrap">
        {entry.runningBalance.toFixed(2)}
      </span>

      <div className="text-right">
        {!isReversed && (
          <button
            onClick={onReverseClick}
            disabled={reversing}
            className="text-[11px] font-mono text-text-faint hover:text-amber transition-colors disabled:opacity-40"
          >
            {reversing ? "…" : "reverse"}
          </button>
        )}
      </div>
    </div>
  );
}
