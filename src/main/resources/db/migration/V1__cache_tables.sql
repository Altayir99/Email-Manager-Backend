-- Phase 1: Local email cache tables
-- Controllers will read from these tables (instant DB reads, no IMAP on request path)

-- folder_state: mirrors IMAP folder metadata per account
CREATE TABLE IF NOT EXISTS folder_state (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
    full_name       VARCHAR(512) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    uid_validity    BIGINT,
    last_seen_uid   BIGINT NOT NULL DEFAULT 0,
    unread_count    INT NOT NULL DEFAULT 0,
    total_count     INT NOT NULL DEFAULT 0,
    last_synced_at  TIMESTAMP,
    UNIQUE (account_id, full_name)
);

-- cached_email: mirrors IMAP envelope metadata per account/folder
CREATE TABLE IF NOT EXISTS cached_email (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
    folder          VARCHAR(512) NOT NULL,
    uid             BIGINT NOT NULL,
    message_id      VARCHAR(512),
    subject         TEXT,
    from_address    VARCHAR(512),
    from_name       VARCHAR(512),
    snippet         TEXT,
    received_at     TIMESTAMP,
    seen            BOOLEAN NOT NULL DEFAULT FALSE,
    has_attachment  BOOLEAN NOT NULL DEFAULT FALSE,
    body_text       TEXT,
    body_html       TEXT,
    body_loaded     BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (account_id, folder, uid)
);

CREATE INDEX IF NOT EXISTS idx_cached_email_list
    ON cached_email (account_id, folder, received_at DESC);

-- account_sync_state: per-account sync bookkeeping (survives restarts)
CREATE TABLE IF NOT EXISTS account_sync_state (
    account_id          UUID PRIMARY KEY REFERENCES email_accounts(id) ON DELETE CASCADE,
    last_full_sync_at   TIMESTAMP,
    last_notified_uid   BIGINT NOT NULL DEFAULT 0,
    sync_status         VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    last_error          TEXT
);
