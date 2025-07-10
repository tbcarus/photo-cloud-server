--liquibase formatted sql

-- changeset author:05-add-checksum-to-media-file
ALTER TABLE media_file ADD COLUMN checksum VARCHAR(64) NOT NULL DEFAULT '';
