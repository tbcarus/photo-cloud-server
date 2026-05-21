--liquibase formatted sql

-- changeset author:11-move-physical-fields-to-stored-object
ALTER TABLE stored_object
    ADD COLUMN file_path          VARCHAR(1024),
    ADD COLUMN filename           VARCHAR(255),
    ADD COLUMN file_extension     VARCHAR(20),
    ADD COLUMN checksum           VARCHAR(64),
    ADD COLUMN size               BIGINT,
    ADD COLUMN detected_mime_type VARCHAR(100),
    ADD COLUMN file_type          VARCHAR(20);

UPDATE stored_object so
SET file_path = regexp_replace(so.storage_key, '/[^/]*$', ''),
    filename = regexp_replace(so.storage_key, '^.*/', ''),
    file_extension = CASE
        WHEN regexp_replace(so.storage_key, '^.*/', '') ~ '\.[0-9a-fA-F-]{36}\.[^.]+$'
            THEN substring(lower(regexp_replace(regexp_replace(so.storage_key, '^.*/', ''), '^.*\.[0-9a-fA-F-]{36}\.([^.]+)$', '\1')) from 1 for 20)
        WHEN regexp_replace(so.storage_key, '^.*/', '') ~ '\.[0-9a-fA-F-]{36}$'
            THEN ''
        WHEN regexp_replace(so.storage_key, '^.*/', '') ~ '\.[^.]+$'
            THEN substring(lower(regexp_replace(regexp_replace(so.storage_key, '^.*/', ''), '^.*\.', '')) from 1 for 20)
        ELSE ''
    END,
    checksum = fi.checksum,
    size = fi.size,
    detected_mime_type = fi.mime_type,
    file_type = fi.file_type
FROM file_item fi
WHERE fi.stored_object_id = so.id;

ALTER TABLE stored_object
    ALTER COLUMN file_path SET NOT NULL,
    ALTER COLUMN filename SET NOT NULL,
    ALTER COLUMN file_extension SET NOT NULL,
    ALTER COLUMN checksum SET NOT NULL,
    ALTER COLUMN size SET NOT NULL,
    ALTER COLUMN detected_mime_type SET NOT NULL,
    ALTER COLUMN file_type SET NOT NULL;

ALTER TABLE stored_object
    ADD CONSTRAINT uk_stored_object_user_checksum UNIQUE (user_id, checksum);

ALTER TABLE file_item
    DROP CONSTRAINT IF EXISTS uk_file_item_user_checksum;

DROP INDEX IF EXISTS idx_file_item_user_file_type;

ALTER TABLE file_item
    DROP COLUMN checksum,
    DROP COLUMN size,
    DROP COLUMN mime_type,
    DROP COLUMN file_type;

ALTER TABLE file_item
    RENAME COLUMN original_filename TO original_name;

ALTER TABLE stored_object
    DROP CONSTRAINT IF EXISTS uk_stored_object_user_storage_key;

ALTER TABLE stored_object
    DROP COLUMN storage_key;
