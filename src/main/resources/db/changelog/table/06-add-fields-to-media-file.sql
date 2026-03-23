--liquibase formatted sql

-- changeset author:05-add-fields-to-media-file
ALTER TABLE media_file
    ADD COLUMN storage_path   VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN thumbnail_path VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN created_at     TIMESTAMP    NOT NULL DEFAULT now();


