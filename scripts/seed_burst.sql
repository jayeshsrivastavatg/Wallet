-- Demo-only data for the public burst script.
-- Run once against the managed Postgres database before submission.
-- Re-running resets Alice and Bob to 100000 paise and removes the
-- get-or-create test wallet so the creation race can be reproduced.

INSERT INTO users (user_id, user_name, bearer_token, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000201', 'Burst Alice', 'burst-alice-token', CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000202', 'Burst Bob', 'burst-bob-token', CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000203', 'Burst New User', 'burst-new-user-token', CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO UPDATE
SET user_name = EXCLUDED.user_name,
    bearer_token = EXCLUDED.bearer_token;

INSERT INTO wallets (wallet_id, user_id, balance_paise, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000301',
     '00000000-0000-0000-0000-000000000201',
     100000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000302',
     '00000000-0000-0000-0000-000000000202',
     100000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO UPDATE
SET balance_paise = EXCLUDED.balance_paise,
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM wallets
WHERE user_id = '00000000-0000-0000-0000-000000000203';
