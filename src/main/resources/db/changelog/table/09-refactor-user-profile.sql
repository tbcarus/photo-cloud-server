--liquibase formatted sql

-- changeset codex:09-refactor-user-profile
ALTER TABLE users ADD COLUMN display_name VARCHAR(128);
UPDATE users
SET display_name = NULLIF(TRIM(CONCAT_WS(' ', first_name, last_name)), '');
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP;
ALTER TABLE users DROP COLUMN first_name;
ALTER TABLE users DROP COLUMN last_name;
