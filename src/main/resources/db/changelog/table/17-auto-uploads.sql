--liquibase formatted sql

--changeset tbcarus:17-auto-uploads
-- Идентификатор автоматического источника хранится только на его корневой папке внутри AutoUploads.
ALTER TABLE folder
    ADD COLUMN backup_source UUID;

ALTER TABLE folder
    ADD CONSTRAINT ck_folder_backup_source_user
        CHECK (backup_source IS NULL OR folder_type = 'USER');

CREATE UNIQUE INDEX uk_folder_user_backup_source
    ON folder (user_id, backup_source)
    WHERE backup_source IS NOT NULL;

CREATE UNIQUE INDEX uk_folder_user_auto_uploads
    ON folder (user_id)
    WHERE folder_type = 'AUTO_UPLOADS';
