--liquibase formatted sql

-- changeset author:03-email-request
CREATE TABLE email_requests
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT email_requests_pkey PRIMARY KEY,
    code        VARCHAR(255)                        NOT NULL,
    type        VARCHAR(255)                        NOT NULL,
    used        BOOLEAN      DEFAULT FALSE          NOT NULL,
    user_id     BIGINT                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMP(6) DEFAULT NOW()          NOT NULL,
    CONSTRAINT email_requests_code_unique UNIQUE (code)
);