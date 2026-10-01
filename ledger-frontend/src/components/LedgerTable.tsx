import { useState } from "react";
import type { LedgerEntryResponse } from "../lib/types";
import { ReverseModal } from "./ReverseModal";

interface Props {
  entries: LedgerEntryResponse[]; // newest first; runningBalance computed by the server
  canReverse: boolean;
  hasMore: boolean;
  loadingMore: boolean;
  onLoadMore: () => void;
  onReverse: (entryId: string, reason: string, idempotencyKey: string) => Promise<void>;
}

export function LedgerTable({ entries, canReverse, hasMore, loadingMore, onLoadMore, onReverse }: Props) {
  const [reversing, setReversing] = useState<LedgerEntryResponse | null>(null);

  if (entries.length === 0) {
    return (
      <div className="border border-dashed border-ink-border rounded-xl py-14 text-center">
        <p className="text-sm text-text-muted">No entries yet.</p>
        <p className="text-xs text-text-faint mt-1 font-mono">the ledger fills in as points move</p>
      </div>
    );
  }

  return (
    <>
      <div className="border border-ink-border rounded-xl overflow-hidden">
        <div className="grid grid-cols-[1fr_auto_auto_auto] gap-3 px-4 py-2.5 border-b border-ink-border bg-ink-raised">
          <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide">Entry</span>
          <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide text-right">Amount</span>
          <span className="text-[11px] font-mono text-text-faint uppercase tracking-wide text-right hidden sm:block">
            Balance
          </span>
          <span className="w-16" />
        </div>

        <div className="divide-y divide-ink-border">
          {entries.map((entry) => (
            <Row
              key={entry.id}
              entry={entry}
              reversible={canReverse && entry.status === "POSTED" && entry.reversalOf === null}
              onReverseClick={() => setReversing(entry)}
            />
          ))}
        </div>

        {hasMore && (
          <button
            onClick={onLoadMore}
            disabled={loadingMore}
            className="w-full py-3 text-xs font-mono text-text-muted hover:text-text-primary border-t border-ink-border
                       hover:bg-ink-raised/40 transition-colors disabled:opacity-40"
          >
            {loadingMore ? "Loading…" : "Load older entries"}
          </button>
        )}
      </div>

      {reversing && (
        <ReverseModal
          entry={reversing}
          onClose={() => setReversing(null)}
          onSubmit={(reason, key) => onReverse(reversing.id, reason, key)}
        />
      )}
    </>
  );
}

function Row({
  entry,
  reversible,
  onReverseClick,
}: {
  entry: LedgerEntryResponse;
  reversible: boolean;
  onReverseClick: () => void;
}) {
  const isReversed = entry.status === "REVERSED";
  const isCredit = entry.type === "CREDIT";
  const isCompensation = entry.reversalOf !== null;

  return (
    <div
      className={`grid grid-cols-[1fr_auto_auto_auto] gap-3 px-4 py-3 items-center transition-colors
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
            <span className="text-[10px] font-mono text-amber px-1.5 py-0.5 rounded bg-amber-bg">↺ reversed</span>
          )}
          {isCompensation && (
            <span className="text-[10px] font-mono text-text-muted px-1.5 py-0.5 rounded bg-ink-raised">
              reversal
            </span>
          )}
        </div>
        <p className="text-sm text-text-primary mt-1 truncate" title={entry.referenceId}>
          {entry.referenceId}
        </p>
        {entry.remarks && <p className="text-xs text-text-muted mt-0.5 truncate">{entry.remarks}</p>}
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

      <span
        className="text-sm font-mono tabular text-right text-text-muted hidden sm:block whitespace-nowrap"
        data-testid="running-balance"
      >
        {entry.runningBalance === null ? "—" : entry.runningBalance.toFixed(2)}
      </span>

      <div className="text-right w-16">
        {reversible && (
          <button
            onClick={onReverseClick}
            aria-label={`Reverse ${entry.referenceId}`}
            className="text-[11px] font-mono text-text-faint hover:text-amber transition-colors"
          >
            reverse
          </button>
        )}
      </div>
    </div>
  );
}
