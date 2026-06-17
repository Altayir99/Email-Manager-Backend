-- Phase 2: Incremental UID sync improvements

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
