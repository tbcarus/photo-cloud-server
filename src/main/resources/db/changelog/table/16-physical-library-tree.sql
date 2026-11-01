--liquibase formatted sql

--changeset tbcarus:16-physical-library-tree
-- Путь файла библиотеки вычисляется из дерева Folder и имени файла; физический ключ остаётся только у Camera.
ALTER TABLE stored_object
    ALTER COLUMN file_path DROP NOT NULL,
    ALTER COLUMN filename DROP NOT NULL,
    ALTER COLUMN file_extension DROP NOT NULL;

ALTER TABLE stored_object
    ADD CONSTRAINT ck_stored_object_location
        CHECK ((file_path IS NULL) = (filename IS NULL));
