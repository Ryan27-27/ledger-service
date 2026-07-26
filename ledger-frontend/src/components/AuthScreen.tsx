import { type FormEvent, useState } from "react";

interface Props {
  onLogin: (username: string, password: string) => Promise<void>;
  onRegister: (username: string, password: string) => Promise<void>;
  loading: boolean;
}

export function AuthScreen({ onLogin, onRegister, loading }: Props) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLocalError(null);
    if (!username.trim() || !password) return;
    if (mode === "register" && password.length < 8) {
      setLocalError("Password must be at least 8 characters");
      return;
    }
    try {
      if (mode === "login") {
        await onLogin(username.trim(), password);
      } else {
        await onRegister(username.trim(), password);
      }
    } catch {
      // parent surfaces the error via the shared error state; nothing else to do here
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-6">
      <div className="w-full max-w-sm">
        <div className="mb-8">
          <div className="flex items-center gap-2 mb-3">
            <span className="h-2 w-2 rounded-full bg-credit" />
            <span className="font-mono text-xs text-text-muted tracking-widest uppercase">
              Ledger
            </span>
          </div>
          <h1 className="font-display text-3xl font-600 leading-tight">
            Every point,<br />accounted for.
          </h1>
          <p className="mt-3 text-sm text-text-muted leading-relaxed">
            {mode === "login"
              ? "Sign in to see your append-only ledger — credits, redemptions, and reversals, each one a permanent row."
              : "Create an account to open a points ledger — your JWT is issued the moment you register."}
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <label className="block">
            <span className="text-xs font-mono text-text-muted uppercase tracking-wide">
              Username
            </span>
            <input
              autoFocus
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="aryan123"
              autoComplete="username"
              className="mt-1.5 w-full bg-ink-panel border border-ink-border rounded-lg px-3.5 py-2.5
                         text-sm font-mono text-text-primary placeholder:text-text-faint
                         focus:border-credit/50 transition-colors"
            />
          </label>

          <label className="block">
            <span className="text-xs font-mono text-text-muted uppercase tracking-wide">
              Password
            </span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              className="mt-1.5 w-full bg-ink-panel border border-ink-border rounded-lg px-3.5 py-2.5
                         text-sm font-mono text-text-primary placeholder:text-text-faint
                         focus:border-credit/50 transition-colors"
            />
          </label>

          {localError && <p className="text-xs font-mono text-debit">{localError}</p>}

          <button
            type="submit"
            disabled={loading || !username.trim() || !password}
            className="w-full bg-credit text-ink font-medium text-sm rounded-lg py-2.5
                       hover:bg-credit/90 disabled:opacity-40 disabled:cursor-not-allowed
                       transition-colors"
          >
            {loading
              ? mode === "login" ? "Signing in…" : "Creating account…"
              : mode === "login" ? "Sign in" : "Create account"}
          </button>
        </form>

        <button
          onClick={() => {
            setMode(mode === "login" ? "register" : "login");
            setLocalError(null);
          }}
          className="mt-4 text-xs font-mono text-text-faint hover:text-text-muted transition-colors"
        >
          {mode === "login" ? "New here? Create an account" : "Already have an account? Sign in"}
        </button>
      </div>
    </div>
  );
}
