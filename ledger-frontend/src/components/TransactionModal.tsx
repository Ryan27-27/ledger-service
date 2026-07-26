import { type FormEvent, useState } from "react";
import type { EntryType } from "../lib/types";

interface Props {
  type: EntryType;
  onClose: () => void;
  onSubmit: (amount: number, referenceId: string) => Promise<void>;
}

const COPY: Record<EntryType, { title: string; verb: string; placeholder: string; accent: string }> = {
  CREDIT: {
    title: "Add points",
    verb: "Credit",
    placeholder: "bill-payment-9821",
    accent: "credit",
  },
  DEBIT: {
    title: "Redeem points",
    verb: "Redeem",
    placeholder: "voucher-redemption-4471",
    accent: "debit",
  },
};

export function TransactionModal({ type, onClose, onSubmit }: Props) {
  const [amount, setAmount] = useState("");
  const [referenceId, setReferenceId] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const copy = COPY[type];

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = Number(amount);
    if (!parsed || parsed <= 0) {
      setLocalError("Enter an amount greater than 0");
      return;
    }
    if (!referenceId.trim()) {
      setLocalError("Reference ID is required");
      return;
    }
    setSubmitting(true);
    setLocalError(null);
    try {
      await onSubmit(parsed, referenceId.trim());
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
    >
      <div
        className="w-full max-w-sm bg-ink-panel border border-ink-border rounded-xl p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-5">
          <h2 className="font-display text-lg font-600">{copy.title}</h2>
          <button
            onClick={onClose}
            className="text-text-faint hover:text-text-muted transition-colors text-lg leading-none"
            aria-label="Close"
          >
            ×
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block">
            <span className="text-xs font-mono text-text-muted uppercase tracking-wide">
              Amount
            </span>
            <div className="mt-1.5 relative">
              <input
                autoFocus
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="0.00"
                className="w-full bg-ink border border-ink-border rounded-lg px-3.5 py-2.5 pr-12
                           text-sm font-mono tabular text-text-primary placeholder:text-text-faint
                           focus:border-credit/50 transition-colors"
              />
              <span className="absolute right-3.5 top-1/2 -translate-y-1/2 text-xs font-mono text-text-faint">
                pts
              </span>
            </div>
          </label>

          <label className="block">
            <span className="text-xs font-mono text-text-muted uppercase tracking-wide">
              Reference ID
            </span>
            <input
              value={referenceId}
              onChange={(e) => setReferenceId(e.target.value)}
              placeholder={copy.placeholder}
              className="mt-1.5 w-full bg-ink border border-ink-border rounded-lg px-3.5 py-2.5
                         text-sm font-mono text-text-primary placeholder:text-text-faint
                         focus:border-credit/50 transition-colors"
            />
          </label>

          {localError && (
            <p className="text-xs font-mono text-debit">{localError}</p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className={`w-full font-medium text-sm rounded-lg py-2.5 transition-colors disabled:opacity-40
              ${
                copy.accent === "credit"
                  ? "bg-credit text-ink hover:bg-credit/90"
                  : "bg-debit text-ink hover:bg-debit/90"
              }`}
          >
            {submitting ? "Processing…" : `${copy.verb} ${amount || "0"} pts`}
          </button>
        </form>
      </div>
    </div>
  );
}
