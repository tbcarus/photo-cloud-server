--liquibase formatted sql

--changeset tbcarus:15-library-name-model
-- Уникальность имён папок обеспечивает uk_folder_user_parent_lower_name из changeset 10; его префикс заменяет обычный индекс.
DROP INDEX IF EXISTS uk_folder_user_parent_name;
DROP INDEX IF EXISTS idx_folder_user_parent;

-- Одинаковые checksum допустимы в обычной библиотеке; duplicate Camera защищает блокировка ROOT в FolderService.
ALTER TABLE file_item
    DROP CONSTRAINT uk_file_item_user_folder_checksum;

CREATE INDEX idx_file_item_user_folder_checksum
    ON file_item (user_id, folder_id, checksum);
