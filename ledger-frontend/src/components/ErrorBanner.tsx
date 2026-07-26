interface Props {
  message: string;
  onDismiss: () => void;
}

export function ErrorBanner({ message, onDismiss }: Props) {
  return (
    <div className="flex items-center justify-between gap-3 bg-debit-bg border border-debit/30 rounded-lg px-4 py-2.5">
      <p className="text-sm text-debit font-mono">{message}</p>
      <button
        onClick={onDismiss}
        className="text-debit/70 hover:text-debit text-sm leading-none flex-shrink-0"
        aria-label="Dismiss"
      >
        ×
      </button>
    </div>
  );
}
