--liquibase formatted sql

-- changeset author:10-replace-media-file-with-file-model
DROP TABLE IF EXISTS media_file CASCADE;

CREATE TABLE stored_object
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT stored_object_pkey PRIMARY KEY,
    user_id     BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    storage_key VARCHAR(1024)                       NOT NULL,
    created_at  TIMESTAMP                           NOT NULL DEFAULT now(),
    CONSTRAINT uk_stored_object_user_storage_key UNIQUE (user_id, storage_key)
);

CREATE TABLE folder
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT folder_pkey PRIMARY KEY,
    user_id     BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    parent_id   BIGINT
        REFERENCES folder (id) ON DELETE CASCADE,
    name        VARCHAR(255)                        NOT NULL,
    folder_type VARCHAR(20)                         NOT NULL,
    created_at  TIMESTAMP                           NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP                           NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_folder_user_parent_lower_name ON folder (user_id, parent_id, lower(name));

CREATE TABLE file_item
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT file_item_pkey PRIMARY KEY,
    user_id          BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    folder_id        BIGINT                              NOT NULL
        REFERENCES folder (id),
    stored_object_id BIGINT                              NOT NULL
        REFERENCES stored_object (id),
    original_filename VARCHAR(255)                       NOT NULL,
    mime_type        VARCHAR(100)                        NOT NULL,
    size             BIGINT                              NOT NULL,
    checksum         VARCHAR(64)                         NOT NULL,
    file_type        VARCHAR(20)                         NOT NULL,
    captured_at      TIMESTAMP                           NOT NULL,
    uploaded_at      TIMESTAMP                           NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMP,
    CONSTRAINT uk_file_item_user_checksum UNIQUE (user_id, checksum)
);

CREATE TABLE file_metadata
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT file_metadata_pkey PRIMARY KEY,
    file_item_id  BIGINT                              NOT NULL UNIQUE
        REFERENCES file_item (id) ON DELETE CASCADE,
    width         INTEGER,
    height        INTEGER,
    duration_sec  INTEGER,
    camera_make   VARCHAR(255),
    camera_model  VARCHAR(255),
    lens_model    VARCHAR(255),
    exposure_time VARCHAR(64),
    f_number      NUMERIC(10, 4),
    iso           INTEGER,
    focal_length  NUMERIC(10, 4),
    latitude      NUMERIC(10, 7),
    longitude     NUMERIC(10, 7)
);

CREATE INDEX idx_file_item_user_captured_uploaded_id ON file_item (user_id, captured_at DESC, uploaded_at DESC, id DESC);
CREATE INDEX idx_file_item_user_file_type ON file_item (user_id, file_type);
CREATE INDEX idx_file_item_folder ON file_item (folder_id);
CREATE INDEX idx_file_item_stored_object ON file_item (stored_object_id);
CREATE INDEX idx_folder_user_parent ON folder (user_id, parent_id);
CREATE INDEX idx_folder_user_type ON folder (user_id, folder_type);
CREATE INDEX idx_stored_object_user ON stored_object (user_id);
