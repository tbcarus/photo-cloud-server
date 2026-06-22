--liquibase formatted sql

--changeset tbcarus:14-file-item-user-folder-checksum
-- Денормализуем checksum в file_item, чтобы защитить уникальность логического файла в папке.
ALTER TABLE file_item
    ADD COLUMN checksum VARCHAR(64);

UPDATE file_item fi
SET checksum = so.checksum
FROM stored_object so
WHERE fi.stored_object_id = so.id;

ALTER TABLE file_item
    ALTER COLUMN checksum SET NOT NULL;

-- Уникальное ограничение защищает от дублей логического файла в одной папке: user + folder + checksum.
ALTER TABLE file_item
    ADD CONSTRAINT uk_file_item_user_folder_checksum UNIQUE (user_id, folder_id, checksum);
