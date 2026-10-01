import { type FormEvent, useState } from "react";
import { newIdempotencyKey } from "../lib/api";
import type { LedgerEntryResponse } from "../lib/types";

interface Props {
  entry: LedgerEntryResponse;
  onClose: () => void;
  onSubmit: (reason: string, idempotencyKey: string) => Promise<void>;
}

export function ReverseModal({ entry, onClose, onSubmit }: Props) {
  // Created once per modal: a retry after a failure re-sends the SAME key.
  const [idempotencyKey] = useState(() => newIdempotencyKey("reverse"));
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setLocalError(null);
    try {
      await onSubmit(reason.trim() || "manual correction", idempotencyKey);
      onClose();
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center px-4 z-50"
      onClick={onClose}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Reverse entry"
        className="w-full max-w-sm bg-ink-panel border border-ink-border rounded-xl p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="font-display text-lg font-600 mb-1">Reverse entry</h2>
        <p className="text-xs text-text-muted mb-5 leading-relaxed">
          Posts a compensating {entry.type === "CREDIT" ? "debit" : "credit"} of{" "}
          <span className="font-mono tabular">{entry.amount.toFixed(2)}</span> pts. The original row is kept
          and marked reversed — history is never rewritten.
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block">
            <span className="text-xs font-mono text-text-muted uppercase tracking-wide">Reason</span>
            <input
              autoFocus
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
              placeholder="granted in error"
              className="mt-1.5 w-full bg-ink border border-ink-border rounded-lg px-3.5 py-2.5
                         text-sm text-text-primary placeholder:text-text-faint focus:border-credit/50 transition-colors"
            />
          </label>

          {localError && <p className="text-xs font-mono text-debit">{localError}</p>}

          <div className="flex gap-3">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 border border-ink-border text-text-muted text-sm rounded-lg py-2.5 hover:text-text-primary transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="flex-1 bg-amber text-ink font-medium text-sm rounded-lg py-2.5 hover:bg-amber/90 disabled:opacity-40 transition-colors"
            >
              {submitting ? "Reversing…" : "Reverse"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
