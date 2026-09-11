-- V1__create_core_tables.sql
-- Core schema for the wallet transfer service

-- ─────────────────────────────────────────
-- Users
-- ─────────────────────────────────────────
CREATE TABLE users (
    user_id       UUID         PRIMARY KEY,
    user_name     VARCHAR(255) NOT NULL,
    bearer_token  VARCHAR(512) NOT NULL UNIQUE,
    other_details TEXT,
    created_at    TIMESTAMP
);

-- ─────────────────────────────────────────
-- Wallets
-- ─────────────────────────────────────────
CREATE TABLE wallets (
    wallet_id     UUID   PRIMARY KEY,
    user_id       UUID   NOT NULL UNIQUE REFERENCES users(user_id),
    balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0),
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP
);

-- ─────────────────────────────────────────
-- Transfers
-- ─────────────────────────────────────────
CREATE TABLE transfers (
    transfer_id      UUID         PRIMARY KEY,
    from_wallet_id   UUID         NOT NULL REFERENCES wallets(wallet_id),
    to_wallet_id     UUID         NOT NULL REFERENCES wallets(wallet_id),
    amount_paise     BIGINT       NOT NULL CHECK (amount_paise > 0),
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    status           VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);

-- Indexes on transfer foreign keys
CREATE INDEX idx_transfers_from_wallet_id ON transfers(from_wallet_id);
CREATE INDEX idx_transfers_to_wallet_id   ON transfers(to_wallet_id);
