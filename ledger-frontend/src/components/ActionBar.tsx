interface Props {
  canCredit: boolean;
  onCredit: () => void;
  onDebit: () => void;
}

export function ActionBar({ canCredit, onCredit, onDebit }: Props) {
  return (
    <div className="flex gap-3">
      {canCredit && (
        <button
          onClick={onCredit}
          className="flex-1 flex items-center justify-center gap-2 bg-credit-bg border border-credit/30
                     text-credit text-sm font-medium rounded-lg py-3 hover:bg-credit/15 transition-colors"
        >
          <span className="font-mono text-base leading-none">+</span> Add points
        </button>
      )}
      <button
        onClick={onDebit}
        className="flex-1 flex items-center justify-center gap-2 bg-debit-bg border border-debit/30
                   text-debit text-sm font-medium rounded-lg py-3 hover:bg-debit/15 transition-colors"
      >
        <span className="font-mono text-base leading-none">–</span> Redeem
      </button>
    </div>
  );
}
