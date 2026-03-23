--liquibase formatted sql

-- changeset author:07-add-indexes-to-media-file-user-checksum
CREATE INDEX IF NOT EXISTS idx_media_file_user_checksum ON media_file (user_id, checksum);

-- changeset author:07-add-indexes-to-media-file-user-created-at
CREATE INDEX IF NOT EXISTS idx_media_file_user_created_at ON media_file (user_id, created_at);

-- changeset author:07-add-indexes-to-media-file-type
CREATE INDEX IF NOT EXISTS idx_media_file_type ON media_file (type);
