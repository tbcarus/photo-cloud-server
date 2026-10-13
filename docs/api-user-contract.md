# User/Profile API contract

Документ описывает текущее состояние пользовательских endpoint-ов. Базовый путь: `/api/v1`.

## Авторизация

Публичные endpoint-ы:

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

Access token живет 20 минут. Refresh token живет 7 дней, хранится в БД и может быть отозван.

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
| 401 | неверный login/password, disabled/banned user, invalid refresh token, нет или невалидный access token |
| 403 | refresh token отозван или принадлежит другому пользователю |
| 404 | refresh token не найден в logout/logout-others |
| 409 | register: аккаунт с этим email уже активирован |
| 429 | register: исчерпан лимит регистраций, заголовок `Retry-After` |
| 501 | зарезервированные endpoint-ы, которые есть в контроллерах, но еще не реализованы |
| 503 | register, password reset request: письмо не удалось сформировать или SMTP его не принял (`EMAIL_NOT_SENT`); сохраненные данные не откатываются |

Письма отправляются синхронно в рамках HTTP-запроса. SMTP-таймауты (`spring.mail.properties.mail.smtp.*`: подключение 5 с, каждое чтение и каждая запись 5 с) ограничивают отдельные сетевые операции, а не всю отправку; DNS ими не ограничен, поэтому общий максимум времени ответа не гарантируется.

## Register

### `POST /api/v1/auth/register`

Публичный endpoint. Приводит email к lowercase.

- Новый email: создает disabled user с ролью `USER` и password hash, создает email-code типа `ACTIVATE`.
- Email неактивированного user (повторная регистрация): заменяет password hash, помечает все прежние неиспользованные `ACTIVATE`-коды использованными (старые ссылки отклоняются), создает новый код. Новый user не создается.
- Email активированного user: `409`, данные не меняются, письмо не отправляется.

Изменения БД выполняются в одной транзакции; письмо со ссылкой отправляется после commit.

Лимит: не больше `auth.request.active-max` (по умолчанию 3) `ACTIVATE`-кодов user, созданных в окне `auth.request.activation-valid-days` (по умолчанию 3 дня). Учитываются все такие коды: код первичной регистрации, использованные, аннулированные и коды, письмо для которых не отправилось. Код учитывается, пока не истек его срок действия. При исчерпании лимита — `429`, данные не меняются, письмо не отправляется.

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
| `email` | not blank, valid email, length ≤ 128 |
| `password` | not blank, length 4..20 |

Нарушение правил → `400 VALIDATION_ERROR` с сообщением по полю в `fieldErrors`. Email длиннее 128 символов: `"email": "Email length must be up to 128"`.

Response `201 Created`:

```json
{
  "message": "Email was sent"
}
```

Response `409 Conflict` (`code: CONFLICT`):

```json
{
  "id": "4b0f6a3e-6c4f-4f43-9d7e-2a1f9f0c2b11",
  "code": "CONFLICT",
  "message": "Account with this email is already activated. Please log in",
  "fieldErrors": null
}
```

Response `429 Too Many Requests` с заголовком `Retry-After: 259140` (секунды до истечения кода, после которого попытка снова доступна); в `message` то же время, округленное вверх до минут:

```json
{
  "id": "8d3c2f4a-1b7e-4c55-a0f2-6e9b8d7c5a43",
  "code": "TOO_MANY_REQUESTS",
  "message": "Registration attempts limit reached. Try again in 2 d 23 h 59 min. If you cannot restore access yourself, contact my-cld@yandex.ru",
  "fieldErrors": null
}
```

Response `503 Service Unavailable` — письмо не удалось сформировать или SMTP его не принял (в том числе по таймауту). User и код, сохраненные до отправки, остаются в БД, код учитывается в лимите; повторная регистрация создает новый код:

```json
{
  "id": "5c1e7a2d-3f4b-4a8e-9d61-0b2c7e8f9a10",
  "code": "EMAIL_NOT_SENT",
  "message": "Registration data was saved, but the verification email could not be sent. Please try again later or contact my-cld@yandex.ru.",
  "fieldErrors": null
}
```

### `GET /api/v1/auth/register/confirm?code=...`

Публичный endpoint. Подтверждает регистрацию по коду `ACTIVATE`, если код существует, не использован и не истек.

Response `200 OK`:

```text
User user@example.com was verified
```

Ошибка неверного/использованного/истекшего кода возвращается как `400 BAD_REGISTRATION_REQUEST`.

### `POST /api/v1/auth/register/resend`

Публичный endpoint-заглушка.

Response `501 Not Implemented`:

```json
{
  "message": "Registration confirmation resend is not implemented yet"
}
```

TODO: реализовать повторную отправку с cooldown, лимитом попыток и проверкой истечения кода.

## Login

### `POST /api/v1/auth/login`

Публичный endpoint. Принимает email/password, проверяет enabled/banned, обновляет `lastLoginAt`, выпускает access и refresh token.

Request:

```json
{
  "email": "user@example.com",
  "password": "pass1"
}
```

Response `200 OK`:

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

Неверный email, пароль, disabled user и banned user возвращаются одинаково: `401 INVALID_CREDENTIALS`.

## Refresh Token

### `POST /api/v1/auth/refresh-token`

Публичный endpoint. Принимает refresh token, ищет его в БД, проверяет тип `REFRESH`, срок действия, subject и флаг `revoked`. Возвращает только новый access token.

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
| 401 | token не найден, malformed, expired, неправильный token type |
| 403 | token найден, но `revoked=true` |

## Logout

### `POST /api/v1/auth/logout`

Protected endpoint. Отзывает один refresh token, если он принадлежит текущему пользователю.

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

### `POST /api/v1/auth/logout-all`

Protected endpoint. Отзывает все неотозванные refresh token текущего пользователя.

Response `200 OK`:

```json
{
  "message": "All logged out successfully"
}
```

### `POST /api/v1/auth/logout-others`

Protected endpoint. Сначала проверяет, что переданный refresh token принадлежит текущему пользователю, затем отзывает все остальные active refresh token этого пользователя.

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

## Password Reset

### `POST /api/v1/auth/password/reset/request?email=...`

Публичный endpoint. Создает email-code типа `PASSWORD_RESET` и отправляет письмо.

Response `200 OK`:

```json
{
  "message": "Email was sent"
}
```

Если пользователь не найден, возвращается `400 BAD_REQUEST`.

Response `503 Service Unavailable` — письмо не удалось сформировать или SMTP его не принял; созданный код остается в БД:

```json
{
  "id": "9a4d2c1b-7e6f-4b3a-8c5d-1e2f3a4b5c6d",
  "code": "EMAIL_NOT_SENT",
  "message": "The password reset email could not be sent. Please try again later.",
  "fieldErrors": null
}
```

### `POST /api/v1/auth/password/reset/confirm`

Публичный endpoint. Проверяет code типа `PASSWORD_RESET`, меняет пароль и помечает использованными все активные password reset code текущего пользователя.

Request:

```json
{
  "password": "newPass1",
  "code": "5761d001-0660-4052-9945-b2a83103a692"
}
```

Response `200 OK`:

```json
{
  "message": "Password was reset"
}
```

Важно: текущая реализация не отзывает refresh token после смены пароля.

### Reserved password endpoints

| Method | Path | Status | Response |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/password/reset/resend` | 501 | `{"message":"Password reset resend is not implemented yet"}` |
| GET | `/api/v1/auth/password/reset/page?code=...` | 501 | `{"message":"Password reset page is not implemented yet"}` |
| POST | `/api/v1/auth/password/reset/page` | 501 | `{"message":"Password reset page submit is not implemented yet"}` |

## Profile

### `GET /api/v1/profile`

Protected endpoint. Возвращает текущего authenticated user.

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

Эти endpoint-ы требуют JWT, но сейчас являются заглушками:

| Method | Path | Status |
| --- | --- | --- |
| PATCH | `/api/v1/profile` | 501 |
| GET | `/api/v1/profile/settings` | 501 |
| PATCH | `/api/v1/profile/settings` | 501 |

TODO: определить DTO, validation и правила изменения профиля/settings.

## Root/Test

| Method | Path | Auth | Response |
| --- | --- | --- | --- |
| GET | `/api/v1/test` | no | `{"message":"All good! Permit all connection"}` |
| GET | `/api/v1/test/auth` | yes | `{"message":"All good! Authenticated connection. Hello user@example.com"}` |
