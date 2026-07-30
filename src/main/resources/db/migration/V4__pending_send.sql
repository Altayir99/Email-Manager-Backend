-- V4: Persistent undo-send queue.
-- Pending sends previously lived only in memory (ScheduledFuture); a restart
-- inside the 10s undo window lost the mail (neither sent nor cancelled).
-- This table persists them so a startup recovery pass can finish the job.

CREATE TABLE IF NOT EXISTS pending_send (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                UUID NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
    to_address                VARCHAR(512) NOT NULL,
    cc_addresses              TEXT,
    bcc_addresses             TEXT,
    subject                   TEXT,
    body_text                 TEXT,
    body_html                 TEXT,
    in_reply_to               VARCHAR(512),
    attachment_bytes          BYTEA,
    attachment_filename       VARCHAR(512),
    attachment_content_type   VARCHAR(255),
    send_at                   TIMESTAMP NOT NULL,
    status                    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at                TIMESTAMP NOT NULL
);

-- Recovery loads only rows still awaiting delivery.
CREATE INDEX IF NOT EXISTS idx_pending_send_status
    ON pending_send (status, send_at);
