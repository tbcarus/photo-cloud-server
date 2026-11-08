--liquibase formatted sql

--changeset tbcarus:19-notification
-- Уведомление пользователю. Заголовок и текст — снимок на момент события; target — указатель на объект, не право доступа.
-- Внешних ключей на folder/file_item нет: уведомление переживает удаление объекта и отзыв Share.
CREATE TABLE notification
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT notification_pkey PRIMARY KEY,
    recipient_id BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(32)                         NOT NULL,
    title        TEXT                                NOT NULL,
    body         TEXT                                NOT NULL,
    target_type  VARCHAR(16),
    target_id    BIGINT,
    created_at   TIMESTAMP WITH TIME ZONE            NOT NULL DEFAULT now(),
    read_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_notification_target_pair
        CHECK ((target_type IS NULL) = (target_id IS NULL)),
    CONSTRAINT ck_notification_share_granted_target
        CHECK (type <> 'SHARE_GRANTED' OR target_type IS NOT NULL)
);

-- Список получателя от новых к старым; обслуживает и каскад удаления пользователя.
CREATE INDEX idx_notification_recipient_created ON notification (recipient_id, created_at DESC, id DESC);

-- Счётчик непрочитанных и «прочитать все».
CREATE INDEX idx_notification_recipient_unread ON notification (recipient_id) WHERE read_at IS NULL;
