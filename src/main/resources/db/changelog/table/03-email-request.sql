--liquibase formatted sql

-- changeset author:03-email-request
CREATE TABLE email_requests
(
    id          INTEGER GENERATED ALWAYS AS IDENTITY NOT NULL
        CONSTRAINT email_requests_pkey PRIMARY KEY,
    user_id     INTEGER                              NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,
    code        VARCHAR(255)                         NOT NULL,
    type        VARCHAR(255)                         NOT NULL,
    create_date TIMESTAMP(6) DEFAULT NOW()           NOT NULL,
    used        BOOLEAN      DEFAULT FALSE           NOT NULL,
    CONSTRAINT email_requests_code_unique UNIQUE (code)
);