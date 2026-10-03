-- Zentraler Login (Keycloak) und Dateizugriff auf Nextcloud
CREATE TABLE oidc_states (
    state         TEXT PRIMARY KEY,
    code_verifier TEXT NOT NULL,
    nonce         TEXT NOT NULL,
    app_redirect  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE oidc_accounts (
    user_id            UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    subject            TEXT NOT NULL UNIQUE,
    username           TEXT NOT NULL,
    groups             TEXT NOT NULL DEFAULT '',
    access_token_enc   TEXT,
    access_expires_at  TIMESTAMPTZ,
    refresh_token_enc  TEXT,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
