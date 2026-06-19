-- V3: Add attachment_names column to cached_email for persistent attachment list
ALTER TABLE cached_email ADD COLUMN IF NOT EXISTS attachment_names TEXT;
