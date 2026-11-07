--liquibase formatted sql

--changeset tbcarus:18-share
-- Доступ к файлу или папке: USER — зарегистрированному получателю, LINK — по секрету ссылки.
-- Отзыв удаляет запись, истёкшая запись остаётся: её статус вычисляется по expires_at.
CREATE TABLE share
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT share_pkey PRIMARY KEY,
    owner_id        BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    folder_id       BIGINT
        REFERENCES folder (id) ON DELETE CASCADE,
    file_item_id    BIGINT
        REFERENCES file_item (id) ON DELETE CASCADE,
    kind            VARCHAR(10)                         NOT NULL,
    recipient_id    BIGINT
        REFERENCES users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64),
    encrypted_token TEXT,
    expires_at      TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE            NOT NULL DEFAULT now(),
    CONSTRAINT ck_share_single_target
        CHECK ((folder_id IS NULL) <> (file_item_id IS NULL)),
    CONSTRAINT ck_share_kind_fields
        CHECK ((kind = 'USER' AND recipient_id IS NOT NULL AND token_hash IS NULL AND encrypted_token IS NULL)
            OR (kind = 'LINK' AND recipient_id IS NULL AND token_hash IS NOT NULL AND encrypted_token IS NOT NULL)),
    CONSTRAINT ck_share_not_self
        CHECK (recipient_id IS NULL OR recipient_id <> owner_id),
    CONSTRAINT uk_share_token_hash UNIQUE (token_hash)
);

-- NULL не совпадает с NULL: ограничение действует только на USER-доступ к объекту своего вида,
-- LINK-доступов на объект может быть несколько. Индексы обслуживают и проверку чтения, и каскад удаления объекта.
CREATE UNIQUE INDEX uk_share_folder_recipient ON share (folder_id, recipient_id);
CREATE UNIQUE INDEX uk_share_file_item_recipient ON share (file_item_id, recipient_id);

-- Каскад удаления пользователя и будущие списки выданных и полученных доступов.
CREATE INDEX idx_share_owner ON share (owner_id);
CREATE INDEX idx_share_recipient ON share (recipient_id);
