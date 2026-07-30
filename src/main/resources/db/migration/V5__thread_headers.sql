-- V5: Reference-chain threading (Gmail-style).
-- Store the RFC 5322 In-Reply-To and References headers so conversations can be
-- grouped by the actual reply chain (message_id links) instead of only by
-- subject. Old cached rows have NULLs here → the subject-based fallback applies;
-- newly synced rows thread exactly.

ALTER TABLE cached_email
    ADD COLUMN IF NOT EXISTS in_reply_to    VARCHAR(512),
    ADD COLUMN IF NOT EXISTS msg_references TEXT;
