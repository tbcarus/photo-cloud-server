# API Contract

**Source of truth:** current server code in `src/main/java`, plus `docs/api-endpoint-migration.md` where it matches the implementation. This document describes the API as implemented now, not the desired future design.

## Global conventions

- Base path: `/api/v1`
- Authentication scheme for protected endpoints: `Authorization: Bearer <accessToken>`
- Access token lifetime in code: 20 minutes
- Refresh token lifetime in code: 7 days
- Unless explicitly listed as public below, endpoints are protected by `SecurityConfig` and require authentication.
- JSON 401 body from `JsonAuthenticationEntryPoint`:

```json
{
  "uuid": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "message": "Unauthorized: access token expired or invalid"
}
```

- JSON 403 body from `JsonAccessDeniedHandler`:

```json
{
  "uuid": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "message": "Forbidden"
}
```

- Common structured error body from `GlobalExceptionHandler` for handled domain errors:

```json
{
  "uuid": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "message": "..."
}
```

- Validation errors from `MethodArgumentNotValidException` use a different shape:

```json
{
  "email": "Must be e-mail",
  "password": "Password length must be from 4 to 20"
}
```

- Implemented handled mappings:
- `400 Bad Request`: `EntityNotFoundException`, `IllegalArgumentException`, `TickerRequestException`, bean validation failures
- `401 Unauthorized`: `InvalidCredentialsException`, `InvalidRefreshTokenException`
- `403 Forbidden`: `TokenRevokedException`, `RefreshTokenOwnershipException`
- `404 Not Found`: `RefreshTokenNotFoundException`, `MediaFileNotFoundException`, custom `FileNotFoundException`
- `409 Conflict`: `DuplicateEmailException`
  - `400 Bad Request`: `BadRegistrationRequest`
  - `403 Forbidden`: `TokenRevokedException`
  - `500 Internal Server Error`: `DataIntegrityViolationException` with message `Database constraint violation`
- `BadRegistrationRequest` is thrown by registration confirmation / password reset code validation paths and is mapped to `400 Bad Request` with the common `ErrorResponse` envelope.

## DTO reference

### `LoginRequest`

```json
{
  "email": "user@example.com",
  "password": "pass1"
}
```

Fields:
- `email: string` — `@NotBlank`, `@Email`
- `password: string` — `@NotBlank`, `@Size(min = 4, max = 20)`

### `LoginResponse`

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

### `RefreshRequest`

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

### `RefreshResponse`

```json
{
  "accessToken": "<jwt-access-token>"
}
```

### `LogoutRequest`

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

### `RegisterRequest`

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Fields:
- `email: string` — `@NotBlank`, `@Email`
- `password: string` — required by `@NotBlank`, length `4..20`

### `PasswordResetConfirmRequest`

```json
{
  "password": "newPass1",
  "code": "<password-reset-code>"
}
```

Fields:
- `password: string` — `@NotBlank`, `@Size(min = 4, max = 20)`
- `code: string` — `@NotBlank`

### `UserDto`

```json
{
  "id": 1,
  "email": "user@example.com",
  "firstName": null,
  "lastName": null,
  "enabled": true,
  "banned": false,
  "roles": ["USER"],
  "createAt": "2026-05-17T10:15:30",
  "lastUpdate": "2026-05-17T10:15:30"
}
```

> Note: field name is `createAt`, not `createdAt`, because that is how `UserDto` is declared.

### `MediaFileDto`

```json
{
  "id": 42,
  "originalFilename": "photo.jpg",
  "storageFilename": "photo.jpg.550e8400-e29b-41d4-a716-446655440000.jpg",
  "mimeType": "image/jpeg",
  "size": 123456,
  "type": "IMAGE",
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "uploadedAt": "2026-05-17T10:15:30"
}
```

### `MediaFileChecksumDto`

```json
{
  "originalFilename": "photo.jpg",
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

## Endpoint catalog

### RootController

#### `GET /api/v1/test`
- Authorization: **No**
- Request: no params, no body
- Success: `200 OK`
- Response DTO: inline `Map<String, String>`

```json
{"message":"All good! Permit all connection"}
```

- Errors:
  - `400`: not expected from current implementation
  - `401`: not applicable; endpoint is public
  - `404`: not expected
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `RootController.testPermitAll`
  - request DTO: none
  - response DTO: inline map
  - service method: none

#### `GET /api/v1/test/auth`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
- Success: `200 OK`
- Response DTO: inline `Map<String, String>`

```json
{"message":"All good! Authenticated connection. Hello user@example.com"}
```

- Errors:
  - `400`: not expected
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not expected
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `RootController.testAuth`
  - request DTO: none
  - response DTO: inline map
  - service method: none

### AuthController

#### `POST /api/v1/auth/login`
- Authorization: **No**
- Request:
  - body DTO: `LoginRequest`

```json
{
  "email": "user@example.com",
  "password": "pass1"
}
```

- Success: `200 OK`
- Response DTO: `LoginResponse`

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

- Errors:
  - `400`:
    - validation error for invalid email / password length
  - `401`: wrong email or wrong password (`InvalidCredentialsException`), returned with the same generic error message for both cases
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `AuthController.login`
  - request DTO: `LoginRequest`
  - response DTO: `LoginResponse`
  - service method: `UserService.login`

#### `POST /api/v1/auth/refresh-token`
- Authorization: **No**
- Request:
  - body DTO: `RefreshRequest`

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

- Success: `200 OK`
- Response DTO: `RefreshResponse`

```json
{
  "accessToken": "<new-jwt-access-token>"
}
```

- Errors:
  - `400`: request validation failure
  - `401`: unknown, malformed, expired, wrong-token-type, or otherwise invalid refresh token (`InvalidRefreshTokenException`)
  - `403`: refresh token exists but is revoked (`TokenRevokedException`)
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `AuthController.refresh`
  - request DTO: `RefreshRequest`
  - response DTO: `RefreshResponse`
  - service methods: `UserService.refreshToken`, `JwtService.getRefreshToken`, `JwtService.refreshAccessToken`

#### `POST /api/v1/auth/logout`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - body DTO: `LogoutRequest`

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

- Success: `200 OK`
- Response DTO: inline map

```json
{"message":"Logged out successfully"}
```

- Errors:
  - `400`: request validation failure
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `403`: refresh token exists but belongs to another authenticated user (`RefreshTokenOwnershipException`)
  - `404`: refresh token not found (`RefreshTokenNotFoundException`)
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `AuthController.logout`
  - request DTO: `LogoutRequest`
  - response DTO: inline map
  - service methods: `UserService.logout`, `JwtService.revokeOwnedToken`, `JwtService.revoke`
- Ownership rule:
  - the request refresh token must belong to the authenticated user before it can be revoked

#### `POST /api/v1/auth/logout-all`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - body: none
- Success: `200 OK`
- Response DTO: inline map

```json
{"message":"All logged out successfully"}
```

- Errors:
  - `400`: not expected from current implementation
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `AuthController.logoutAll`
  - request DTO: none
  - response DTO: inline map
  - service methods: `UserService.logoutAll`, `JwtService.revokeAll`

#### `POST /api/v1/auth/logout-others`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - body DTO: `LogoutRequest`

```json
{
  "refreshToken": "<current-refresh-token>"
}
```

- Success: `200 OK`
- Response DTO: inline map

```json
{"message":"All other logged out successfully"}
```

- Errors:
  - `400`: request validation failure
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `403`: supplied current refresh token belongs to another authenticated user (`RefreshTokenOwnershipException`)
  - `404`: supplied current refresh token not found (`RefreshTokenNotFoundException`)
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `AuthController.logoutOthers`
  - request DTO: `LogoutRequest`
  - response DTO: inline map
  - service methods: `UserService.logoutOther`, `JwtService.revokeOtherOwnedToken`, `JwtService.revokeOther`
- Ownership rule:
  - the supplied current refresh token must belong to the authenticated user before any other sessions are revoked

### RegisterController

#### `POST /api/v1/auth/register`
- Authorization: **No**
- Request:
  - body DTO: `RegisterRequest`

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

- Success: `201 Created`
- Response DTO: inline map

```json
{"message":"Email was sent"}
```

- Errors:
  - `400`:
    - validation failure
  - `409`: duplicate email (`DuplicateEmailException`)
  - `401`: not applicable; endpoint is public
  - `404`: not used
  - `500`: possible generic failure; email-send failure is logged and swallowed after user creation
- Java classes:
  - controller method: `RegisterController.register`
  - request DTO: `RegisterRequest`
  - response DTO: inline map
  - service method: `UserService.register`

#### `GET /api/v1/auth/register/confirm?code=...`
- Authorization: **No**
- Request:
  - query params: `code: string`
- Success: `200 OK`
- Response DTO: plain string

```text
User user@example.com was verified
```

- Errors:
  - `400`: not currently guaranteed by implementation
  - `401`: not applicable; endpoint is public
  - `404`: not currently guaranteed by implementation
  - `409`: not used
  - `400`: invalid / used / expired confirmation code (`BadRegistrationRequest`)
- Java classes:
  - controller method: `RegisterController.verifyEmail`
  - request DTO: none
  - response DTO: plain string
  - service method: `EmailRequestService.confirmRegistration`

#### `POST /api/v1/auth/register/resend`
- Authorization: **No**
- Request: none
- Success: endpoint is reserved, not implemented
- Response: `501 Not Implemented`

```json
{"message":"Registration confirmation resend is not implemented yet"}
```

- Errors:
  - `400`, `401`, `404`, `409`: not used by current stub
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `RegisterController.resendVerifyEmail`
  - request DTO: none
  - response DTO: inline map
  - service method: none

### PasswordController

#### `POST /api/v1/auth/password/reset/request?email=...`
- Authorization: **No**
- Request:
  - query params: `email: string`
- Success: `200 OK`
- Response DTO: inline map

```json
{"message":"Email was sent"}
```

- Errors:
  - `400`: blank `email` validation failure; user not found (`EntityNotFoundException`)
  - `401`: not applicable; endpoint is public
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `PasswordController.forgotPassword`
  - request DTO: none
  - response DTO: inline map
  - service method: `UserService.forgotPassword`

#### `POST /api/v1/auth/password/reset/confirm`
- Authorization: **No**
- Request:
  - body DTO: `PasswordResetConfirmRequest`

```json
{
  "password": "newPass1",
  "code": "<password-reset-code>"
}
```
- Success: `200 OK`
- Response DTO: inline map

```json
{"message":"Password was reset"}
```

- Errors:
  - `400`: blank `password` / `code` validation failure; invalid / used / expired reset code (`BadRegistrationRequest`)
  - `401`: not applicable; endpoint is public
  - `404`: not currently guaranteed by implementation
  - `409`: not used
- Java classes:
  - controller method: `PasswordController.resetPassword`
  - request DTO: `PasswordResetConfirmRequest`
  - response DTO: inline map
  - service methods: `UserService.resetPassword`, `EmailRequestService.resetPassword`

#### `POST /api/v1/auth/password/reset/resend`
- Authorization: **No**
- Response: `501 Not Implemented`

```json
{"message":"Password reset resend is not implemented yet"}
```

- Java classes:
  - controller method: `PasswordController.resendResetPassword`
  - request DTO: none
  - response DTO: inline map
  - service method: none

#### `GET /api/v1/auth/password/reset/page?code=...`
- Authorization: **No**
- Request:
  - query params: `code: string`
- Response: `501 Not Implemented`

```json
{"message":"Password reset page is not implemented yet"}
```

- Java classes:
  - controller method: `PasswordController.getResetPasswordPage`
  - request DTO: none
  - response DTO: inline map
  - service method: none

#### `POST /api/v1/auth/password/reset/page`
- Authorization: **No**
- Request: no declared body / params
- Response: `501 Not Implemented`

```json
{"message":"Password reset page submit is not implemented yet"}
```

- Java classes:
  - controller method: `PasswordController.submitResetPasswordPage`
  - request DTO: none
  - response DTO: inline map
  - service method: none

### UserController

#### `GET /api/v1/profile`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
- Success: `200 OK`
- Response DTO: `UserDto`

```json
{
  "id": 1,
  "email": "user@example.com",
  "firstName": null,
  "lastName": null,
  "enabled": true,
  "banned": false,
  "roles": ["USER"],
  "createAt": "2026-05-17T10:15:30",
  "lastUpdate": "2026-05-17T10:15:30"
}
```

- Errors:
  - `400`: not expected
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `UserController.getProfile`
  - request DTO: none
  - response DTO: `UserDto`
  - service method: none

#### `PATCH /api/v1/profile`
- Authorization: **Yes**
- Response: `501 Not Implemented`

```json
{"message":"Profile update is not implemented yet"}
```

- Java classes:
  - controller method: `UserController.updateProfile`
  - request DTO: none declared
  - response DTO: inline map
  - service method: none

#### `GET /api/v1/profile/settings`
- Authorization: **Yes**
- Response: `501 Not Implemented`

```json
{"message":"Profile settings are not implemented yet"}
```

- Java classes:
  - controller method: `UserController.getSettings`
  - request DTO: none
  - response DTO: inline map
  - service method: none

#### `PATCH /api/v1/profile/settings`
- Authorization: **Yes**
- Response: `501 Not Implemented`

```json
{"message":"Profile settings update is not implemented yet"}
```

- Java classes:
  - controller method: `UserController.updateSettings`
  - request DTO: none declared
  - response DTO: inline map
  - service method: none

### MediaFileController

#### `POST /api/v1/media`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - multipart form-data part: `file`
- Success: `200 OK`
- Response DTO: `MediaFileDto`

```json
{
  "id": 42,
  "originalFilename": "photo.jpg",
  "storageFilename": "photo.jpg.550e8400-e29b-41d4-a716-446655440000.jpg",
  "mimeType": "image/jpeg",
  "size": 123456,
  "type": "IMAGE",
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "uploadedAt": "2026-05-17T10:15:30"
}
```

- Duplicate upload behavior:
  - duplicate detection key: `(currentUser.id, sha256(file bytes))`
  - if duplicate exists, no new DB row and no new physical file are created
  - response is still `200 OK`
  - body is the **existing** `MediaFileDto`
  - the original filename in the response is the first stored file name, not the newly uploaded duplicate name
- Errors:
  - `400`:
    - empty file
    - blank / missing MIME type
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not used
  - `409`: not used for duplicates
  - `500`: metadata persistence failure (`DataIntegrityViolationException`) or other unexpected failure
- Java classes:
  - controller method: `MediaFileController.uploadFile`
  - request DTO: none; multipart part `file`
  - response DTO: `MediaFileDto`
  - service method: `MediaFileService.uploadFile`

#### `GET /api/v1/media?page=0&size=10`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - query params:
    - `page: int`, default `0`
    - `size: int`, default `10`
- Success: `200 OK`
- Response DTO: Spring Data `Page<MediaFileDto>`

```json
{
  "content": [
    {
      "id": 42,
      "originalFilename": "photo.jpg",
      "storageFilename": "photo.jpg.550e8400-e29b-41d4-a716-446655440000.jpg",
      "mimeType": "image/jpeg",
      "size": 123456,
      "type": "IMAGE",
      "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "uploadedAt": "2026-05-17T10:15:30"
    }
  ],
  "pageable": {"...": "Spring Data page metadata"},
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 10,
  "number": 0,
  "sort": {"...": "createdAt desc"},
  "first": true,
  "numberOfElements": 1,
  "empty": false
}
```

- Errors:
  - `400`: possible if pagination params fail to bind
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `MediaFileController.getUserFiles`
  - request DTO: none
  - response DTO: `Page<MediaFileDto>`
  - service method: `MediaFileService.getUserFiles`

#### `GET /api/v1/media/{id}`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - path params: `id: long`
- Success: `200 OK`
- Response DTO: `MediaFileDto`
- Errors:
  - `400`: path binding failure for invalid `id`
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: returned both when the media file does not exist **and** when it belongs to another user
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `MediaFileController.getFile`
  - request DTO: none
  - response DTO: `MediaFileDto`
  - service methods: `MediaFileService.getFileDtoForCurrentUser`, `MediaFileService.getFileForCurrentUser`

#### `GET /api/v1/media/{id}/download`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - path params: `id: long`
- Success: `200 OK`
- Response DTO: binary `Resource`
- Response headers:
  - `Content-Type`: file MIME type from stored metadata
  - `Content-Disposition`: `attachment; filename="<originalFilename>"`
- Example success body: raw file bytes, not JSON
- Errors:
  - `400`: path binding failure for invalid `id`
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`:
    - media file not found
    - media file belongs to another user
    - DB row exists but physical file is missing or unreadable
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `MediaFileController.downloadFile`
  - request DTO: none
  - response DTO: `Resource`
  - service method: `MediaFileService.getFileForCurrentUser`

#### `PATCH /api/v1/media/{id}`
- Authorization: **Yes**
- Response: `501 Not Implemented`

```json
{"message":"Media metadata update is not implemented yet"}
```

- Java classes:
  - controller method: `MediaFileController.updateFile`
  - request DTO: none declared
  - response DTO: inline map
  - service method: none

#### `DELETE /api/v1/media/{id}`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
  - path params: `id: long`
- Success: `204 No Content`
- Response body: empty
- Errors:
  - `400`: path binding failure for invalid `id`
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: returned both when the media file does not exist **and** when it belongs to another user
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `MediaFileController.deleteFile`
  - request DTO: none
  - response DTO: none
  - service method: `MediaFileService.deleteFileForCurrentUser`

#### `GET /api/v1/media/{id}/thumbnail`
- Authorization: **Yes**
- Response: `501 Not Implemented`

```json
{"message":"Media thumbnail is not implemented yet"}
```

- Java classes:
  - controller method: `MediaFileController.getThumbnail`
  - request DTO: none
  - response DTO: inline map
  - service method: none

#### `POST /api/v1/media/check-exist`
- Authorization: **Yes**
- Request: no declared DTO/body in current code
- Response: `501 Not Implemented`

```json
{"message":"Single checksum check is not implemented yet"}
```

- Java classes:
  - controller method: `MediaFileController.checkExist`
  - request DTO: none declared
  - response DTO: inline map
  - service method: none

#### `POST /api/v1/media/checksums/exists`
- Authorization: **Yes**
- Request: no declared DTO/body in current code
- Response: `501 Not Implemented`

```json
{"message":"Batch checksum check is not implemented yet"}
```

- Java classes:
  - controller method: `MediaFileController.checkChecksumsExist`
  - request DTO: none declared
  - response DTO: inline map
  - service method: none

#### `GET /api/v1/media/checksums`
- Authorization: **Yes**
- Request:
  - header: `Authorization: Bearer <accessToken>`
- Success: `200 OK`
- Response DTO: `List<MediaFileChecksumDto>`

```json
[
  {
    "originalFilename": "photo.jpg",
    "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
]
```

- Errors:
  - `400`: not expected
  - `401`: missing, expired, malformed, invalid, or wrong-token-type bearer token
  - `404`: not used
  - `409`: not used
  - `500`: possible only as generic unexpected server failure
- Java classes:
  - controller method: `MediaFileController.getChecksums`
  - request DTO: none
  - response DTO: `List<MediaFileChecksumDto>`
  - service method: `MediaFileService.getChecksumsForUser`

## Client migration notes

### DTOs the Android client should have now

Required for currently implemented flows:
- `LoginRequest(email, password)`
- `LoginResponse(accessToken, refreshToken)`
- `RefreshRequest(refreshToken)`
- `RefreshResponse(accessToken)`
- `LogoutRequest(refreshToken)`
- `RegisterRequest(email, password)`
- `PasswordResetConfirmRequest(password, code)`
- `UserDto`
- `MediaFileDto`
- `MediaFileChecksumDto`
- Generic handled error DTO: `ErrorResponse(uuid, message)`
- Validation error model: map of field name to message
- 401 error model: `ErrorResponse(uuid, message)`
- Spring Data page wrapper for `Page<MediaFileDto>`

### Required fields for client requests

- `LoginRequest`
  - `email`
  - `password`
- `RegisterRequest`
  - `email`
  - `password`
- `RefreshRequest`
  - `refreshToken`
- `LogoutRequest`
  - `refreshToken`
- media upload
  - multipart part `file`
- password reset request
  - query param `email`
- password reset confirm
  - body DTO `PasswordResetConfirmRequest`
- registration confirm
  - query param `code`

### Endpoints already usable by the Android client

- `GET /api/v1/test`
- `GET /api/v1/test/auth`
- `POST /api/v1/auth/register`
- `GET /api/v1/auth/register/confirm`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/logout-all`
- `POST /api/v1/auth/logout-others`
- `POST /api/v1/auth/password/reset/request`
- `POST /api/v1/auth/password/reset/confirm`
- `GET /api/v1/profile`
- `POST /api/v1/media`
- `GET /api/v1/media`
- `GET /api/v1/media/{id}`
- `GET /api/v1/media/{id}/download`
- `DELETE /api/v1/media/{id}`
- `GET /api/v1/media/checksums`

### TODO / NOT IMPLEMENTED endpoints

- `POST /api/v1/auth/register/resend`
- `POST /api/v1/auth/password/reset/resend`
- `GET /api/v1/auth/password/reset/page`
- `POST /api/v1/auth/password/reset/page`
- `PATCH /api/v1/profile`
- `GET /api/v1/profile/settings`
- `PATCH /api/v1/profile/settings`
- `PATCH /api/v1/media/{id}`
- `GET /api/v1/media/{id}/thumbnail`
- `POST /api/v1/media/check-exist`
- `POST /api/v1/media/checksums/exists`

### Errors the client should handle

- `400`
  - validation map for DTO validation failures
  - structured `ErrorResponse` for many domain failures
  - login failure currently arrives here, not as `401`
  - missing refresh token in DB also arrives here
- `401`
  - any protected endpoint without a valid access token
  - body shape differs from `ErrorResponse`
- `403`
  - revoked refresh token during refresh
- `404`
  - media metadata / download / delete for missing media
  - the same `404` is returned for someone else’s media file; the client cannot distinguish foreign vs nonexistent media
- `501`
  - reserved TODO endpoints
- `500`
  - generic unexpected failure
  - metadata persistence failure during upload

## Contract issues / questions

1. **Wrong-credential login uses `401 Unauthorized`.**
   - Current behavior: unknown email and wrong password both return the same generic `ErrorResponse` message so the API does not reveal which field was wrong.

2. **Duplicate registration uses `409 Conflict`.**
   - Current behavior: duplicate email -> `DuplicateEmailException` -> `409`.

3. **Validation error format is intentionally separate from domain/security errors.**
   - Current behavior: bean validation returns a field map, while domain/security errors use `ErrorResponse`.
   - Recommendation: keep this distinction explicit in the client contract unless the API later adopts a richer unified envelope with field errors.

4. **Logout and logout-others are bound to the authenticated principal.**
   - `logout` rejects a foreign refresh token with `403 Forbidden`.
   - `logout-others` verifies the supplied current refresh token before revoking other sessions, so a foreign token cannot degrade into an accidental logout-all.

5. **List response exposes raw Spring Data `Page` JSON.**
   - Current contract couples the client to Spring serialization details.
   - Recommendation: consider a dedicated page DTO if mobile compatibility / long-term stability matters.

6. **`storageFilename` is exposed to the client.**
   - It is an internal storage concern and may not be useful to Android.
   - Recommendation: decide whether the public DTO should expose only client-relevant fields.

7. **`UserDto.createAt` looks like a naming typo.**
   - Recommendation: decide whether to keep as-is for compatibility or rename before the client ships.

8. **Some docs examples describe future request bodies for not-implemented endpoints that controllers do not currently accept.**
    - Example: profile update, settings update, checksum-check endpoints.
    - Recommendation: keep future examples clearly marked as non-contractual until DTOs exist.

9. **`MediaFileResponse` exists but is not used by active controller endpoints.**
    - Current implemented media responses use `MediaFileDto`.
    - Recommendation: remove or adopt one canonical media response shape before clients multiply.

10. **`docs/api-endpoint-migration.md` is mostly accurate, but it is a migration map, not a full contract.**
    - It also mentions future checksum/filter examples that are not implemented in current controllers.
    - Recommendation: treat this file as historical support only; use the present document as the contract source for the client.
