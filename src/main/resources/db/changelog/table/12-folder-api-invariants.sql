--liquibase formatted sql

--changeset tbcarus:12-folder-api-invariants
CREATE UNIQUE INDEX uk_folder_user_root
    ON folder (user_id)
    WHERE parent_id IS NULL AND folder_type = 'ROOT';

CREATE UNIQUE INDEX uk_folder_user_parent_name
    ON folder (user_id, parent_id, lower(name))
    WHERE parent_id IS NOT NULL;

ALTER TABLE folder
    ADD CONSTRAINT ck_folder_not_self_parent
        CHECK (parent_id IS NULL OR parent_id <> id);
