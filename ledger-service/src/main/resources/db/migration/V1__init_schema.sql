CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL UNIQUE,
    cached_balance  NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id               UUID PRIMARY KEY,
    account_id       UUID NOT NULL REFERENCES accounts(id),
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    type             VARCHAR(10) NOT NULL CHECK (type IN ('CREDIT', 'DEBIT')),
    status           VARCHAR(10) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'REVERSED')),
    reference_id     VARCHAR(255) NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    remarks          VARCHAR(500),
    created_at       TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key)
);

-- Hot paths: balance replay (audit) and per-account history both filter by account_id
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries(account_id, created_at);
