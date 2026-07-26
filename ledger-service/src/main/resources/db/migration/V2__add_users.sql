CREATE TABLE app_users (
    id              UUID PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    account_id      UUID NOT NULL UNIQUE REFERENCES accounts(id),
    role            VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_users_username ON app_users(username);
