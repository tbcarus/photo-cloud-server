--liquibase formatted sql

-- changeset author:04-create-media-file-table
CREATE TABLE media_file
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT media_file_pkey PRIMARY KEY,
    original_filename VARCHAR(255)                        NOT NULL,
    storage_filename  VARCHAR(255)                        NOT NULL,
    mime_type         VARCHAR(100)                        NOT NULL,
    size              BIGINT                              NOT NULL,
    type              VARCHAR(20)                         NOT NULL,
    created_at        TIMESTAMP                           NOT NULL DEFAULT now(),
    user_id           BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE
);