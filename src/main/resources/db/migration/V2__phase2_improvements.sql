-- Phase 2: Incremental UID sync improvements
--
-- NOTE: V1 ran against a DB that already had these tables created by Hibernate
-- (ddl-auto), so CREATE TABLE IF NOT EXISTS silently no-op'd.  When that old
-- container was torn down, the cache tables were dropped.  We recreate them
-- here idempotently so V2 is self-contained and works whether or not the
-- tables currently exist.

-- ── Recreate cache tables if they were lost ──────────────────────────────────

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

CREATE TABLE IF NOT EXISTS account_sync_state (
    account_id          UUID PRIMARY KEY REFERENCES email_accounts(id) ON DELETE CASCADE,
    last_full_sync_at   TIMESTAMP,
    last_notified_uid   BIGINT NOT NULL DEFAULT 0,
    sync_status         VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    last_error          TEXT
);

-- ── Phase 2 additions ─────────────────────────────────────────────────────────

-- Store To/CC recipients so full email detail can be served from cache
ALTER TABLE cached_email
    ADD COLUMN IF NOT EXISTS to_addresses   TEXT,
    ADD COLUMN IF NOT EXISTS cc_addresses   TEXT;

-- Index on uid for fast deletion-detection range queries
CREATE INDEX IF NOT EXISTS idx_cached_email_uid
    ON cached_email (account_id, folder, uid DESC);

-- Track when we last reconciled flags so we don't re-reconcile every 30s
ALTER TABLE folder_state
    ADD COLUMN IF NOT EXISTS last_flag_reconcile_at TIMESTAMP;
