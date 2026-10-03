-- Konten, Workspaces und Rollen
CREATE TABLE users (
    id                   UUID PRIMARY KEY,
    email                TEXT NOT NULL,
    first_name           TEXT,
    use_case             TEXT,
    phone                TEXT,
    phone_verified_at    TIMESTAMPTZ,
    anti_phishing_phrase TEXT,
    marketing_consent    BOOLEAN NOT NULL DEFAULT FALSE,
    login_method         TEXT NOT NULL DEFAULT 'email',
    locked_at            TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_idx ON users (lower(email));

CREATE TABLE workspaces (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'guest')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);

-- Geräte mit hardwaregebundenem Schlüssel (öffentlicher Teil)
CREATE TABLE devices (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    public_key         BYTEA NOT NULL,
    key_fingerprint    TEXT NOT NULL,
    name               TEXT NOT NULL,
    platform           TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_ip            TEXT,
    last_country       TEXT,
    last_city          TEXT,
    last_connection    TEXT,
    revoked_at         TIMESTAMPTZ
);
CREATE INDEX devices_user_idx ON devices (user_id);
CREATE UNIQUE INDEX devices_user_key_idx ON devices (user_id, key_fingerprint);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id   UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_device_idx ON refresh_tokens (device_id);

-- Einmalcodes (E-Mail-Login, Telefon, Zusatzbestätigung)
CREATE TABLE challenges (
    id           UUID PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind IN ('email_login', 'phone_verify', 'step_up')),
    user_id      UUID REFERENCES users (id) ON DELETE CASCADE,
    target       TEXT NOT NULL,
    code_hash    TEXT NOT NULL,
    attempts     INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    payload      TEXT,
    ip           TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ
);
CREATE INDEX challenges_target_idx ON challenges (target, created_at);

-- Sicherheitsereignisse (Audit und Grundlage der Risikoprüfung)
CREATE TABLE auth_events (
    id          UUID PRIMARY KEY,
    user_id     UUID REFERENCES users (id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    device_id   UUID,
    ip          TEXT,
    country     TEXT,
    city        TEXT,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    connection  TEXT,
    risk_score  INT,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_events_user_idx ON auth_events (user_id, created_at DESC);

-- Einmal-Links aus Sicherheits-E-Mails („Das war ich nicht“)
CREATE TABLE security_links (
    token_hash TEXT PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

-- Versandprotokoll der Onboarding-Strecke (jede E-Mail höchstens einmal)
CREATE TABLE email_log (
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    template_key TEXT NOT NULL,
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, template_key)
);

-- Chats
CREATE TABLE conversations (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    title        TEXT NOT NULL,
    model        TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX conversations_user_idx ON conversations (user_id, updated_at DESC);

CREATE TABLE messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    model           TEXT,
    input_tokens    INT,
    output_tokens   INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_conversation_idx ON messages (conversation_id, created_at);
