--liquibase formatted sql

--changeset tbcarus:13-file-api-operations
ALTER TABLE stored_object
    DROP CONSTRAINT IF EXISTS uk_stored_object_user_checksum;

CREATE INDEX IF NOT EXISTS idx_stored_object_user_checksum
    ON stored_object (user_id, checksum);

CREATE INDEX IF NOT EXISTS idx_file_item_user_folder_lower_original_name
    ON file_item (user_id, folder_id, lower(original_name));
