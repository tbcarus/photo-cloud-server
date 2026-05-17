--liquibase formatted sql

-- changeset author:08-alter-refresh-token-token-to-text
ALTER TABLE refresh_token
    ALTER COLUMN token TYPE TEXT;
