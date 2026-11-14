--liquibase formatted sql
--changeset codex:20-upload-sessions
CREATE TABLE upload_sessions (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    client_request_id UUID NOT NULL,
    folder_id BIGINT NOT NULL,
    backup_source UUID,
    original_filename VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL CHECK (size > 0),
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    confirmed_offset BIGINT NOT NULL DEFAULT 0 CHECK (confirmed_offset >= 0 AND confirmed_offset <= size),
    state VARCHAR(16) NOT NULL CHECK (state IN ('UPLOADING', 'FINALIZING', 'COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    result_json TEXT,
    error VARCHAR(255),
    publication_path TEXT,
    cleaned BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (user_id, client_request_id),
    CHECK ((state IN ('UPLOADING', 'FINALIZING')) = (finished_at IS NULL)),
    CHECK ((state = 'COMPLETED') = (result_json IS NOT NULL))
);
CREATE INDEX upload_sessions_publication ON upload_sessions(user_id) WHERE publication_path IS NOT NULL;
CREATE INDEX upload_sessions_activity ON upload_sessions(state, last_activity_at);
