CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_transaction_key
ON ledger (
    account_last4,
    occurred_at,
    direction,
    amount
);
