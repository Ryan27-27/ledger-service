-- V3: correctness + integrity hardening.
--
-- 1. Timestamps -> TIMESTAMPTZ. Hibernate 6 maps java.time.Instant to
--    TIMESTAMP WITH TIME ZONE, so `ddl-auto: validate` rejects plain TIMESTAMP.
ALTER TABLE accounts       ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE accounts       ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';
ALTER TABLE ledger_entries ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
ALTER TABLE app_users      ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';

-- 2. Idempotency keys are scoped per account (a key used by one customer must
--    never collide with, or leak the result of, another customer's request).
ALTER TABLE ledger_entries DROP CONSTRAINT uk_idempotency_key;
ALTER TABLE ledger_entries ADD CONSTRAINT uk_ledger_account_idempotency UNIQUE (account_id, idempotency_key);

-- 3. Reversal linkage. A compensating entry points at the entry it reverses,
--    and the partial unique index guarantees an entry is reversed at most once
--    even if application-level checks are bypassed or race.
ALTER TABLE ledger_entries ADD COLUMN reversal_of UUID REFERENCES ledger_entries(id);
CREATE UNIQUE INDEX uq_ledger_reversal_of ON ledger_entries(reversal_of) WHERE reversal_of IS NOT NULL;

-- 4. Defence in depth: the cached balance can never go negative, even if a
--    code path forgets the balance check.
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance_non_negative CHECK (cached_balance >= 0);

-- 5. Enforce "append-only" in the database itself. Rows can never be deleted,
--    and the only column that may change after insert is `status`
--    (POSTED -> REVERSED, never back).
CREATE FUNCTION ledger_entries_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger_entries is append-only: DELETE is not allowed';
    END IF;

    IF NEW.id              IS DISTINCT FROM OLD.id
    OR NEW.account_id      IS DISTINCT FROM OLD.account_id
    OR NEW.amount          IS DISTINCT FROM OLD.amount
    OR NEW.type            IS DISTINCT FROM OLD.type
    OR NEW.reference_id    IS DISTINCT FROM OLD.reference_id
    OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
    OR NEW.remarks         IS DISTINCT FROM OLD.remarks
    OR NEW.reversal_of     IS DISTINCT FROM OLD.reversal_of
    OR NEW.created_at      IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ledger_entries is append-only: only status may change';
    END IF;

    IF OLD.status = 'REVERSED' AND NEW.status <> 'REVERSED' THEN
        RAISE EXCEPTION 'ledger_entries: a REVERSED entry can never be re-opened';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_guard
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_guard();
