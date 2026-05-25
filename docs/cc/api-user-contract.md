# User/Profile API contract

Документ описывает текущее состояние пользовательских endpoint-ов. Базовый путь: `/api/v1`.

## Авторизация

Публичные endpoint-ы (не требуют токена):

| Method | Path |
| --- | --- |
| GET | `/api/v1/test` |
| POST | `/api/v1/auth/register` |
| GET | `/api/v1/auth/register/confirm?code=...` |
| POST | `/api/v1/auth/register/resend` |
| POST | `/api/v1/auth/login` |
| POST | `/api/v1/auth/refresh-token` |
| POST | `/api/v1/auth/password/reset/request?email=...` |
| POST | `/api/v1/auth/password/reset/confirm` |
| POST | `/api/v1/auth/password/reset/resend` |
| GET | `/api/v1/auth/password/reset/page?code=...` |
| POST | `/api/v1/auth/password/reset/page` |

Все остальные endpoint-ы требуют `Authorization: Bearer <accessToken>`.

- Access token — JWT, тип `ACCESS`, срок жизни 20 минут.
- Refresh token — JWT, тип `REFRESH`, срок жизни 7 дней, хранится в таблице `refresh_token`, может быть отозван.
- Фильтр `JwtAuthenticationFilter` проверяет только ACCESS-токены; попытка передать refresh token как access token вернет `401`.

## Ошибки

Общий JSON-формат ошибок:

```json
{
  "id": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": {
    "email": "Email must be valid"
  }
}
```

Основные статусы:

| Status | Когда |
| --- | --- |
| 400 | validation error, malformed body, missing query param, неверный/использованный/истекший email-code |
| 401 | неверный login/password, disabled/banned user, invalid/expired/mistyped refresh token |
| 403 | refresh token найден в БД, но `revoked=true` или принадлежит другому пользователю |
| 404 | refresh token не найден в БД при logout/logout-others |
| 409 | email уже зарегистрирован |
| 501 | зарезервированные endpoint-ы: есть в контроллерах, но не реализованы |

## Register

### `POST /api/v1/auth/register`

Публичный endpoint. Создает disabled user, приводит email к lowercase, назначает роль `USER`, сохраняет hash пароля, генерирует email-code типа `ACTIVATE`, отправляет письмо.

Request:

```json
{
  "email": "user@example.com",
  "password": "pass1"
}
```

Validation:

| Field | Правила |
| --- | --- |
| `email` | not blank, valid email format |
| `password` | not blank, length 4–20 |

Response `201 Created`:

```json
{
  "message": "Email was sent"
}
```

Ошибки при отправке email логируются, но не откатывают создание пользователя. Повторная регистрация с тем же email: `409 CONFLICT`.

### `GET /api/v1/auth/register/confirm?code=...`

Публичный endpoint. Ищет email-code типа `ACTIVATE`, проверяет что код не использован и не истек (7 дней), помечает код использованным, переводит пользователя в `enabled=true`.

Response `200 OK`:

```text
User user@example.com was verified
```

При неверном, использованном или истекшем коде: `400 BAD_REGISTRATION_REQUEST`.

### `POST /api/v1/auth/register/resend`

Публичный endpoint-заглушка.

Response `501 Not Implemented`:

```json
{
  "message": "Registration confirmation resend is not implemented yet"
}
```

TODO: реализовать повторную отправку кода с cooldown, лимитом попыток и проверкой актуальности кода.

## Login

### `POST /api/v1/auth/login`

Публичный endpoint. Принимает email/password, проверяет `enabled` и `banned`, обновляет `lastLoginAt`, выпускает access token и refresh token.

Request:

```json
{
  "email": "user@example.com",
  "password": "pass1"
}
```

Validation: те же правила, что в register (not blank, email format, password length 4–20).

Response `200 OK`:

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

Неверный email, неверный пароль, `disabled=true` и `banned=true` возвращают одинаковый `401 INVALID_CREDENTIALS` (без различения причины).

## Refresh Token

### `POST /api/v1/auth/refresh-token`

Публичный endpoint. Принимает refresh token, ищет его в БД, проверяет тип `REFRESH`, валидность подписи, срок действия, subject и флаг `revoked`. Возвращает только новый access token (refresh token не ротируется).

Request:

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

Response `200 OK`:

```json
{
  "accessToken": "<new-jwt-access-token>"
}
```

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | пустой `refreshToken` |
| 401 | token не найден в БД, malformed JWT, expired, неправильный `token_type` |
| 403 | token найден в БД, но `revoked=true` |

## Logout

### `POST /api/v1/auth/logout`

Protected endpoint. Отзывает один конкретный refresh token, если он принадлежит текущему пользователю.

Request:

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

Response `200 OK`:

```json
{
  "message": "Logged out successfully"
}
```

Ошибки:

| Status | Когда |
| --- | --- |
| 403 | token принадлежит другому пользователю |
| 404 | token не найден в БД |

### `POST /api/v1/auth/logout-all`

Protected endpoint. Отзывает все неотозванные refresh token текущего пользователя (все устройства/сессии).

Response `200 OK`:

```json
{
  "message": "All logged out successfully"
}
```

### `POST /api/v1/auth/logout-others`

Protected endpoint. Проверяет, что переданный refresh token принадлежит текущему пользователю, затем отзывает все остальные его активные refresh token (оставляет текущую сессию).

Request:

```json
{
  "refreshToken": "<current-refresh-token>"
}
```

Response `200 OK`:

```json
{
  "message": "All other logged out successfully"
}
```

Ошибки:

| Status | Когда |
| --- | --- |
| 403 | token принадлежит другому пользователю |
| 404 | token не найден в БД |

## Password Reset

### `POST /api/v1/auth/password/reset/request?email=...`

Публичный endpoint. Ищет пользователя по email, генерирует email-code типа `PASSWORD_RESET`, отправляет письмо.

Лимит генерации кодов: не более 3 запросов за 3 дня (`EmailRequestService`). Превышение лимита: `400`.

Response `200 OK`:

```json
{
  "message": "Email was sent"
}
```

Если пользователь не найден: `400 BAD_REQUEST`.

### `POST /api/v1/auth/password/reset/confirm`

Публичный endpoint. Ищет email-code типа `PASSWORD_RESET`, проверяет что не использован и не истек, меняет пароль, помечает использованными все активные password reset code пользователя.

Request:

```json
{
  "password": "newPass1",
  "code": "5761d001-0660-4052-9945-b2a83103a692"
}
```

Validation:

| Field | Правила |
| --- | --- |
| `password` | not blank, length 4–20 |
| `code` | not blank |

Response `200 OK`:

```json
{
  "message": "Password was reset"
}
```

При неверном/использованном/истекшем коде: `400`.

**Важно:** текущая реализация не отзывает refresh token после смены пароля. Активные сессии остаются валидными.

### Reserved password endpoints

| Method | Path | Status | Сообщение |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/password/reset/resend` | 501 | `{"message":"Password reset resend is not implemented yet"}` |
| GET | `/api/v1/auth/password/reset/page?code=...` | 501 | `{"message":"Password reset page is not implemented yet"}` |
| POST | `/api/v1/auth/password/reset/page` | 501 | `{"message":"Password reset page submit is not implemented yet"}` |

## Profile

### `GET /api/v1/profile`

Protected endpoint. Возвращает данные текущего аутентифицированного пользователя.

Response `200 OK`:

```json
{
  "id": 1,
  "email": "user@example.com",
  "displayName": null,
  "enabled": true,
  "banned": false,
  "roles": ["USER"],
  "createdAt": "2026-05-17T10:15:30",
  "lastUpdate": "2026-05-17T10:15:30",
  "lastLoginAt": "2026-05-17T10:15:30"
}
```

### Reserved profile endpoints

Protected, но сейчас заглушки (501):

| Method | Path | Описание |
| --- | --- | --- |
| PATCH | `/api/v1/profile` | обновление профиля |
| GET | `/api/v1/profile/settings` | получение настроек |
| PATCH | `/api/v1/profile/settings` | обновление настроек |

TODO: определить DTO, validation и правила для изменения профиля и настроек.

## Root / Test

| Method | Path | Auth | Response |
| --- | --- | --- | --- |
| GET | `/api/v1/test` | нет | `{"message":"All good! Permit all connection"}` |
| GET | `/api/v1/test/auth` | да | `{"message":"All good! Authenticated connection. Hello user@example.com"}` |
