-- Stage 1 of the casino-lab work: the accounts table, kept deliberately
-- separate from `players`. `players` is the existing, already-proven
-- leaderboard feature (Stage 0 confirmed it still works correctly) and
-- stays completely untouched here - `accounts` is the new, real customer
-- identity that the wallet and games will attach to in later stages.
CREATE TABLE accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_accounts_email UNIQUE (email)
);
