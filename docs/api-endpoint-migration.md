# API Endpoint Migration

This document maps the previous server endpoints to the unified `/api/v1` contract for Android client migration.

| Было | Стало | Комментарий |
|---|---|---|
| `GET api/test` | `GET /api/v1/test` | Test endpoint kept for Android client connectivity checks. |
| `GET api/test/auth` | `GET /api/v1/test/auth` | Authenticated test endpoint kept. |
| `POST /api/auth/login` | `POST /api/v1/auth/login` | Moved under the versioned auth API. |
| `POST /api/auth/logout` | `POST /api/v1/auth/logout` | Moved under the versioned auth API. |
| `POST /api/auth/logout-all` | `POST /api/v1/auth/logout-all` | Moved under the versioned auth API. |
| `POST /api/auth/logout-other` | `POST /api/v1/auth/logout-others` | Renamed to use the plural `others`. |
| `POST /api/user/refresh-token` | `POST /api/v1/auth/refresh-token` | Refresh token now belongs to auth, not user. |
| `POST /api/auth/register` | `POST /api/v1/auth/register` | Moved under the versioned register API. |
| `GET /register/ACTIVATE?email=...&code=...` | `GET /api/v1/auth/register/confirm?code=...` | Enum and email were removed from the URL contract. |
| `POST /api/auth/resend-verify-email` | `POST /api/v1/auth/register/resend` | Reserved TODO endpoint for confirmation resend. |
| `POST /api/auth/forgot-password` | `POST /api/v1/auth/password/reset/request` | Renamed to the password reset request scenario. |
| `POST /api/auth/reset-password` | `POST /api/v1/auth/password/reset/confirm` | Renamed to the password reset confirmation scenario. |
| Не было | `POST /api/v1/auth/password/reset/resend` | Reserved TODO endpoint for password reset resend. |
| Не было | `GET /api/v1/auth/password/reset/page?code=...` | Reserved TODO endpoint for browser-based password reset page. |
| Не было | `POST /api/v1/auth/password/reset/page` | Reserved TODO endpoint for browser-based password reset page submit. |
| Не было | `GET /api/v1/profile` | New endpoint for current user profile. |
| Не было | `PATCH /api/v1/profile` | Reserved TODO endpoint for profile update. |
| Не было | `GET /api/v1/profile/settings` | Reserved TODO endpoint for profile settings. |
| Не было | `PATCH /api/v1/profile/settings` | Reserved TODO endpoint for profile settings update. |
| `POST /api/v1/photos/upload` | `POST /api/v1/files` | File upload now uses the canonical files resource. |
| `GET /api/v1/files` | `GET /api/v1/files` | File list now uses the canonical files resource. |
| Не было | `GET /api/v1/files/{id}` | New endpoint for file metadata. |
| `DELETE /api/v1/photos/{id}` | `DELETE /api/v1/files/{id}` | Delete now uses the canonical files resource. |
| `GET /api/v1/photos/{id}/download` | `GET /api/v1/files/{id}/download` | Download now uses the canonical files resource. |
| `GET /api/v1/files/checksums` | `GET /api/v1/files/checksums` | Kept with FileChecksumDto. |
| `ALBUM_URL = /api/v1/albums` | Не перенесено | Album endpoint constants were removed; albums are future scope. |
| `ALBUM_PHOTOS_URL = /api/v1/albums/{id}/photos` | Не перенесено | Album-photo endpoint constants were removed; albums are future scope. |
