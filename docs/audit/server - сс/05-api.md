# 05. API Inventory

> Полная инвентаризация HTTP-API, восстановленная по `@RestController`-классам.
> Base path приложения: `/` (`server.servlet.context-path: /`), порт `8080`. Версионный префикс: `ApiPaths.API_V1 = "/api/v1"`.
> Формат ошибок для всех endpoint'ов, кроме `GET /auth/register/confirm`: `ErrorResponse` (`{id, code, message, fieldErrors}`).

---

## 1. Сводная таблица всех endpoint'ов

| # | Method | URI | Controller#method | Auth | Реализован | В тестах | В документации | Legacy? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | GET | `/api/v1/test` | `RootController.testPermitAll` | нет | ✅ | ❌ | ✅ | — |
| 2 | GET | `/api/v1/test/auth` | `RootController.testAuth` | да | ✅ | ❌ | ✅ | — |
| 3 | POST | `/api/v1/auth/register` | `RegisterController.register` | нет | ✅ | ✅ | ✅ | — |
| 4 | GET | `/api/v1/auth/register/confirm` | `RegisterController.verifyEmail` | нет | ✅ | ✅ | ✅ | — |
| 5 | POST | `/api/v1/auth/register/resend` | `RegisterController.resendVerifyEmail` | нет | ❌ 501 | ❌ | ✅ | — |
| 6 | POST | `/api/v1/auth/login` | `AuthController.login` | нет | ✅ | ✅ | ✅ | — |
| 7 | POST | `/api/v1/auth/logout` | `AuthController.logout` | **да** | ✅ | ✅ | ✅ | — |
| 8 | POST | `/api/v1/auth/logout-all` | `AuthController.logoutAll` | **да** | ✅ | ✅ | ✅ | — |
| 9 | POST | `/api/v1/auth/logout-others` | `AuthController.logoutOthers` | **да** | ✅ | ✅ | ✅ | — |
| 10 | POST | `/api/v1/auth/refresh-token` | `AuthController.refresh` | нет | ✅ | ✅ | ✅ | — |
| 11 | POST | `/api/v1/auth/password/reset/request` | `PasswordController.forgotPassword` | нет | ✅ | ✅ | ✅ | — |
| 12 | POST | `/api/v1/auth/password/reset/confirm` | `PasswordController.resetPassword` | нет | ✅ | ✅ | ✅ | — |
| 13 | POST | `/api/v1/auth/password/reset/resend` | `PasswordController.resendResetPassword` | нет | ❌ 501 | ❌ | ✅ | — |
| 14 | GET | `/api/v1/auth/password/reset/page` | `PasswordController.getResetPasswordPage` | нет | ❌ 501 | ❌ | ✅ | — |
| 15 | POST | `/api/v1/auth/password/reset/page` | `PasswordController.submitResetPasswordPage` | нет | ❌ 501 | ❌ | ✅ | — |
| 16 | GET | `/api/v1/profile` | `UserController.getProfile` | да | ✅ | ✅ | ✅ | — |
| 17 | PATCH | `/api/v1/profile` | `UserController.updateProfile` | да | ❌ 501 | ❌ | ✅ | — |
| 18 | GET | `/api/v1/profile/settings` | `UserController.getSettings` | да | ❌ 501 | ❌ | ✅ | — |
| 19 | PATCH | `/api/v1/profile/settings` | `UserController.updateSettings` | да | ❌ 501 | ❌ | ✅ | — |
| 20 | GET | `/api/v1/folders/root` | `FolderController.getRoot` | да | ✅ | ✅ | ✅ | — |
| 21 | GET | `/api/v1/folders/{id}/children` | `FolderController.getChildren` | да | ✅ | ✅ | ✅ | — |
| 22 | POST | `/api/v1/folders` | `FolderController.createFolder` | да | ✅ | ✅ | ✅ | — |
| 23 | PATCH | `/api/v1/folders/{id}` | `FolderController.renameFolder` | да | ✅ | ✅ | ✅ | — |
| 24 | POST | `/api/v1/folders/{id}/move` | `FolderController.moveFolder` | да | ✅ | ✅ | ✅ | — |
| 25 | DELETE | `/api/v1/folders/{id}` | `FolderController.deleteFolder` | да | ✅ | ✅ | ✅ | — |
| 26 | POST | `/api/v1/files` | `FileController.uploadFile` | да | ✅ | ✅ | ✅ | **да** (комментарий в коде: «Старый upload endpoint сохраняется для совместимости») |
| 27 | POST | `/api/v1/files/upload` | `FileController.uploadFileExplicit` | да | ✅ | ❌ | ✅ | — (дубликат #26) |
| 28 | GET | `/api/v1/files` | `FileController.getUserFiles` | да | ✅ | ✅ | ✅ | — |
| 29 | GET | `/api/v1/files/{id}` | `FileController.getFile` | да | ✅ | ✅ | ✅ | — |
| 30 | GET | `/api/v1/files/{id}/download` | `FileController.downloadFile` | да | ✅ | ✅ | ✅ | — |
| 31 | PATCH | `/api/v1/files/{id}` | `FileController.renameFile` | да | ✅ | ✅ | ✅ | — |
| 32 | POST | `/api/v1/files/{id}/move` | `FileController.moveFile` | да | ✅ | ✅ | ✅ | — |
| 33 | POST | `/api/v1/files/{id}/copy` | `FileController.copyFile` | да | ✅ | ✅ | ✅ | — |
| 34 | DELETE | `/api/v1/files/{id}` | `FileController.deleteFile` | да | ✅ | ✅ | ✅ | — |
| 35 | GET | `/api/v1/files/checksums` | `FileController.getChecksums` | да | ✅ | ✅ | ✅ | **вероятно** («Старый endpoint списка checksum» — `api-file-contract.md`) |
| 36 | POST | `/api/v1/files/checksums/exists` | `FileController.checkExistingChecksums` | да | ✅ | ✅ | ✅ | — |

**Итого:** 36 маппингов, из них 29 реализованы, 7 возвращают `501`.

**Служебные (springdoc, не свои):** `/swagger-ui/**`, `/swagger-resources/*`, `/v3/api-docs/**` — публичны.

---

## 2. RootController

### 2.1. `GET /api/v1/test`

| Аспект | Значение |
| --- | --- |
| Назначение | Проверка связи без авторизации (для Android-клиента) |
| Auth | **нет** (в permitAll `SecurityConfig`) |
| Параметры | нет |
| Response 200 | `{"message":"All good! Permit all connection"}` |
| Побочные эффекты | нет |

### 2.2. `GET /api/v1/test/auth`

| Аспект | Значение |
| --- | --- |
| Auth | **да** — `@AuthenticationPrincipal User` |
| Response 200 | `{"message":"All good! Authenticated connection. Hello <email>"}` |
| Ошибки | `401` без/с невалидным токеном |
| Побочные эффекты | нет |

**[CONFIRMED]** Только `TEST_URL` попал в permitAll; `TEST_AUTH_URL` защищён.

---

## 3. RegisterController (`/api/v1/auth/register`)

### 3.1. `POST /api/v1/auth/register`

| Аспект | Значение |
| --- | --- |
| Auth | нет |
| Request body | `RegisterRequest {email, password}` (`@Validated`) |
| Validation | `email`: `@NotBlank`, `@Email`; `password`: `@NotBlank`, `@Size(4..20)` |
| Response | **`201 Created`**, `{"message":"Email was sent"}` |
| Ошибки | `400 VALIDATION_ERROR`, `409 CONFLICT` («Email is already registered») |
| Побочные эффекты БД | INSERT `users` (`enabled=false, banned=false, roles={USER}`, email в lowercase, BCrypt-пароль); INSERT `user_roles`; INSERT `email_requests` (`type=ACTIVATE`) |
| Файловое хранилище | нет |
| Внешние вызовы | SMTP-отправка письма |

**[CONFIRMED]** Ошибка отправки письма ловится и логируется — пользователь всё равно создаётся.
**[CONFIRMED][RISK]** В catch-блоке `e.getCause().getMessage()` — NPE при `cause == null` → `500`.
**[CONFIRMED]** Поле `displayName` в `RegisterRequest` закомментировано.

### 3.2. `GET /api/v1/auth/register/confirm?code=...`

| Аспект | Значение |
| --- | --- |
| Auth | нет |
| Query | `code` (`@RequestParam String`, **без валидации**) |
| Response | **`200 OK`, `text/plain`**: `User <email> was verified` |
| Ошибки | `400 BAD_REGISTRATION_REQUEST` (код не найден / уже использован / не того типа / истёк — все случаи неразличимы); `400 BAD_REQUEST` при отсутствии параметра |
| Побочные эффекты БД | UPDATE `email_requests.used = true`; UPDATE `users.enabled = true` (через dirty checking в `@Transactional`) |

**[CONFIRMED][INCONSISTENCY]** Единственный endpoint, возвращающий не-JSON при успехе.
**[CONFIRMED]** Прочие активные `ACTIVATE`-коды пользователя **не** инвалидируются (в отличие от password reset).

### 3.3. `POST /api/v1/auth/register/resend`

**[CONFIRMED]** Заглушка: `501`, `{"message":"Registration confirmation resend is not implemented yet"}`. Тело запроса не принимается. TODO в коде: cooldown, лимит попыток, проверка истечения.

---

## 4. AuthController (`/api/v1/auth`)

### 4.1. `POST /api/v1/auth/login`

| Аспект | Значение |
| --- | --- |
| Auth | нет |
| Request | `LoginRequest {email, password}` — `@NotBlank`, `@Email`, `@Size(4..20)` |
| Response 200 | `LoginResponse {accessToken, refreshToken}` |
| Ошибки | `400 VALIDATION_ERROR`; `401 INVALID_CREDENTIALS` — **одинаково** для неизвестного email, неверного пароля, `enabled=false` и `banned=true` |
| Побочные эффекты БД | UPDATE `users.last_login_at`; INSERT `refresh_token` |

**[CONFIRMED][RISK]** `@Size(min=4, max=20)` применяется и к login: пользователь с паролем длиннее 20 символов (созданный в обход API) не сможет войти — валидация отклонит запрос до сверки хеша.
**[CONFIRMED]** Каждый login создаёт **новый** refresh token; старые не отзываются (закомментированный код переиспользования оставлен в `JwtService.generateRefreshToken()`). Соответствует `TODO.txt` п.2.5 «нужна какая-то защита от множественных запросов».

### 4.2. `POST /api/v1/auth/logout`

| Аспект | Значение |
| --- | --- |
| Auth | **да** (нужен валидный access token) |
| Request | `LogoutRequest {refreshToken}` — `@NotBlank` |
| Response 200 | `{"message":"Logged out successfully"}` |
| Ошибки | `400` пустой токен; `401` нет access token; `404 REFRESH_TOKEN_NOT_FOUND`; `403 REFRESH_TOKEN_OWNERSHIP_ERROR` |
| Побочные эффекты БД | UPDATE `refresh_token`: `revoked=true`, `revoked_at=now()` |

### 4.3. `POST /api/v1/auth/logout-all`

| Аспект | Значение |
| --- | --- |
| Auth | да |
| Request body | **нет** |
| Response 200 | `{"message":"All logged out successfully"}` |
| Побочные эффекты | UPDATE всех `refresh_token` пользователя с `revoked=false` → `revoked=true`; **`revoked_at` не заполняется** |

### 4.4. `POST /api/v1/auth/logout-others`

| Аспект | Значение |
| --- | --- |
| Auth | да |
| Request | `LogoutRequest {refreshToken}` — токен, который надо **сохранить** |
| Response 200 | `{"message":"All other logged out successfully"}` |
| Ошибки | `400`, `401`, `404`, `403` (как в logout) |
| Побочные эффекты | Проверка владения переданным токеном → отзыв всех прочих активных токенов; `revoked_at` не заполняется |

**[CONFIRMED]** Тест `logoutOthersWithForeignRefreshTokenReturnsForbiddenWithoutRevokingOwnTokens` подтверждает, что при `403` ничего не отзывается.

### 4.5. `POST /api/v1/auth/refresh-token`

| Аспект | Значение |
| --- | --- |
| Auth | **нет** (публичный) |
| Request | `RefreshRequest {refreshToken}` — `@NotBlank` |
| Response 200 | `RefreshResponse {accessToken}` — **только access token** |
| Ошибки | `400` пустой; `401 INVALID_REFRESH_TOKEN` (не найден в БД / истёк / неверный тип / subject не совпал / malformed); `403 REFRESH_TOKEN_REVOKED` |
| Побочные эффекты | **нет записей в БД** |

**[CONFIRMED]** Refresh token **не ротируется**: клиент продолжает использовать тот же самый.
**[CONFIRMED][RISK]** Проверяются только сам токен и флаг `revoked`; `enabled`/`banned` пользователя **не проверяются** — забаненный пользователь продолжит получать access token'ы до истечения refresh (до 7 дней).

---

## 5. PasswordController (`/api/v1/auth/password`)

Класс помечен `@Validated` (нужно для валидации `@RequestParam`).

### 5.1. `POST /api/v1/auth/password/reset/request?email=...`

| Аспект | Значение |
| --- | --- |
| Auth | нет |
| Query | `email` — `@NotBlank(message="Email must not be blank")`; **формат email не проверяется** |
| Response 200 | `{"message":"Email was sent"}` |
| Ошибки | `400 VALIDATION_ERROR` (blank); `400 BAD_REQUEST` «User <email> not found» — **[RISK]** user enumeration; `400` при отсутствии параметра |
| Побочные эффекты | INSERT `email_requests` (`type=PASSWORD_RESET`); отправка письма |

**[CONFIRMED]** Rate-limit отсутствует: `UserService.forgotPassword()` вызывает `generateEmailRequest()` напрямую, минуя `checkAndGenerateCode()`.

### 5.2. `POST /api/v1/auth/password/reset/confirm`

| Аспект | Значение |
| --- | --- |
| Auth | нет |
| Request | `PasswordResetConfirmRequest {password, code}` — `@NotBlank`, `@Size(4..20)` для пароля |
| Response 200 | `{"message":"Password was reset"}` |
| Ошибки | `400 VALIDATION_ERROR`; `400 BAD_REGISTRATION_REQUEST` (код неверен/использован/истёк/не того типа) |
| Побочные эффекты | UPDATE `users.password` (BCrypt); UPDATE `email_requests.used=true` для использованного кода **и для всех активных `PASSWORD_RESET`-кодов пользователя за последние 3 дня** |

**[CONFIRMED][RISK]** Refresh token'ы **не отзываются** — это явно зафиксировано и в `docs/api-user-contract.md`.
**[CONFIRMED][RISK]** `EmailRequestService.resetPassword()` использует `userRepository.findById(...).get()` без orElseThrow.
**[CONFIRMED]** Тест `passwordResetConfirmRejectsLegacyQueryParamContract` подтверждает, что старый query-параметрический контракт больше не поддерживается.

### 5.3–5.5. Заглушки

| Endpoint | Ответ |
| --- | --- |
| `POST /reset/resend` | `501 {"message":"Password reset resend is not implemented yet"}` |
| `GET /reset/page?code=...` | `501 {"message":"Password reset page is not implemented yet"}` (`code` — `@RequestParam String`, обязателен → без него `400`) |
| `POST /reset/page` | `501 {"message":"Password reset page submit is not implemented yet"}` |

**[CONFIRMED][INCONSISTENCY]** `EmailService.getEmailContext()` формирует ссылку письма восстановления пароля именно на `PasswordController.RESET_PAGE_URL`, то есть **письмо ведёт на 501-заглушку**.

---

## 6. UserController (`/api/v1/profile`)

### 6.1. `GET /api/v1/profile`

| Аспект | Значение |
| --- | --- |
| Auth | да |
| Response 200 | `UserDto {id, email, displayName, enabled, banned, roles[], createdAt, lastUpdate, lastLoginAt}` |
| Побочные эффекты | нет — DTO собирается прямо из `@AuthenticationPrincipal User`, повторного чтения из БД нет |

**[CONFIRMED]** Пароль в DTO не попадает. Тест `profileUsesCurrentUserFields` проверяет отсутствие полей `firstName`/`lastName` и наличие `createdAt`/`lastLoginAt`.

### 6.2–6.4. Заглушки

`PATCH /profile`, `GET /profile/settings`, `PATCH /profile/settings` → `501` с соответствующим `message`. Тело запроса не принимается ни одним из них. Требуют авторизации (не в permitAll).

---

## 7. FolderController (`/api/v1/folders`)

Все endpoint'ы требуют авторизации. Все возвращают `FolderDto {id, parentId, name, folderType, createdAt, updatedAt}`.

### 7.1. `GET /api/v1/folders/root`

| Аспект | Значение |
| --- | --- |
| Response 200 | `FolderDto` ROOT-папки |
| Побочные эффекты | **INSERT `folder`** (ROOT), если его ещё нет — endpoint не является read-only |
| Ошибки | `401` |

### 7.2. `GET /api/v1/folders/{id}/children`

| Аспект | Значение |
| --- | --- |
| Path | `id` — папка-родитель |
| Response 200 | `FolderDto[]` — **только прямые потомки**, отсортированы по `lower(name), id` |
| Ошибки | `404 NOT_FOUND` (нет или чужая); `401` |
| Побочные эффекты | нет |

**[CONFIRMED]** Файлы в ответ не входят — только подпапки.

### 7.3. `POST /api/v1/folders`

| Аспект | Значение |
| --- | --- |
| Request | `CreateFolderRequest {parentId?, name}` — `name`: `@NotBlank @Size(max=255)` |
| Response | **`200 OK`** (не `201`), `FolderDto` |
| Ошибки | `400 BAD_REQUEST` (blank после trim, >255 после trim, попытка создать внутри `CAMERA`/`FILES`); `404` (parent чужой/нет); `409 CONFLICT` (имя занято или зарезервировано `Camera`/`Files` в ROOT); `401` |
| Побочные эффекты | INSERT `folder` с `folder_type='USER'`; при `parentId=null` может создать ROOT |

### 7.4. `PATCH /api/v1/folders/{id}`

| Аспект | Значение |
| --- | --- |
| Request | `RenameFolderRequest {name}` |
| Response 200 | `FolderDto` |
| Ошибки | `400` (системная папка, blank/длинное имя); `404`; `409`; `401` |
| Побочные эффекты | UPDATE `folder.name`, `folder.updated_at` |

### 7.5. `POST /api/v1/folders/{id}/move`

| Аспект | Значение |
| --- | --- |
| Request | `MoveFolderRequest {targetParentId}` — `@NotNull` |
| Response 200 | `FolderDto` |
| Ошибки | `400` (системная папка, target = сама папка, target — потомок, target = `CAMERA`/`FILES`); `404`; `409`; `401` |
| Побочные эффекты | UPDATE `folder.parent_id`, `updated_at` |

**[CONFIRMED]** `targetParentId` обязателен → переместить папку в ROOT можно только явно указав id ROOT.

### 7.6. `DELETE /api/v1/folders/{id}`

| Аспект | Значение |
| --- | --- |
| Response | **`204 No Content`**, без тела |
| Ошибки | `400` (системная папка, есть подпапки, есть файлы); `404`; `401` |
| Побочные эффекты | DELETE `folder` (только пустой `USER`) |

---

## 8. FileController (`/api/v1/files`)

Все endpoint'ы требуют авторизации.

### 8.1. `POST /api/v1/files` и `POST /api/v1/files/upload`

**[CONFIRMED]** Два маппинга, одна реализация (`fileItemService.uploadFile(file, user, folderId)`).

| Аспект | Значение |
| --- | --- |
| Content-Type | `multipart/form-data` |
| Параметры | `file` (`@RequestParam MultipartFile`, обязателен); `folderId` (`@RequestParam(required=false) Long`) |
| Response | **`200 OK`** (не `201`), `FileItemDto` |
| Ошибки | `400 BAD_REQUEST` («Файл пустой» — `IllegalArgumentException`); `400` при отсутствии `file`; `401`; `404` (чужой/несуществующий `folderId`); `409 CONFLICT` (конфликт имени вне CAMERA); `413 FILE_TOO_LARGE` (превышен `storage.max-file-size-bytes` или лимит multipart); `500 DATABASE_CONSTRAINT_VIOLATION` (нераспознанное нарушение constraint) |
| Побочные эффекты БД | при новой загрузке: INSERT `stored_object`, INSERT `file_item`, опционально INSERT `file_metadata`; возможен ленивый INSERT `folder` (ROOT/CAMERA/FILES) |
| Файловое хранилище | создание temp-файла → `ATOMIC_MOVE` в `<root>/users/{id}/objects/{cs0:2}/{cs2:4}/{name}_{uuid}.{ext}`; при дубле temp удаляется, новых файлов нет |

**[CONFIRMED] Идемпотентность:** повторная загрузка тех же байтов в ту же папку возвращает `200` с **уже существующим** `FileItemDto` (в том числе с прежним `originalName`, даже если имя файла в новом запросе другое) и не создаёт ни физического файла, ни строк БД.

**[CONFIRMED]** `Content-Type` части multipart игнорируется — MIME определяется Tika по содержимому.

### 8.2. `GET /api/v1/files`

| Аспект | Значение |
| --- | --- |
| Query | `page` (default `0`), `size` (default `10`), `folderId` (optional) |
| Сортировка | фиксированная: `capturedAt DESC, uploadedAt DESC, id DESC` (задаётся в контроллере, клиент менять не может) |
| Response 200 | `PageResponse<FileItemDto> {items[], page, size, totalElements, totalPages, hasNext, hasPrevious}` |
| Ошибки | `401`; `404` (чужой/несуществующий `folderId`); `400` при нечисловом `page`/`size` |
| Побочные эффекты | нет |

**[CONFIRMED]** Без `folderId` возвращаются **все** файлы пользователя из всех папок (не только из корня).
**[CONFIRMED]** С `folderId` — **только прямые** файлы папки, без рекурсии.
**[CONFIRMED][RISK]** Верхняя граница `size` не задана.

### 8.3. `GET /api/v1/files/{id}`

| Аспект | Значение |
| --- | --- |
| Response 200 | `FileItemDto` |
| Ошибки | `404 FILE_ITEM_NOT_FOUND` (нет или чужой); `401` |
| Побочные эффекты | нет |

### 8.4. `GET /api/v1/files/{id}/download`

| Аспект | Значение |
| --- | --- |
| Response 200 | raw bytes; `Content-Type: StoredObject.detectedMimeType`; `Content-Disposition: attachment; filename="<FileItem.originalName>"` |
| Ошибки | `404 FILE_ITEM_NOT_FOUND` — и когда записи нет/чужая, **и когда физический файл отсутствует или нечитаем**; `401` |
| Побочные эффекты | чтение с диска через `UrlResource` |

**[CONFIRMED][RISK]** `Content-Disposition` собирается конкатенацией без экранирования: имя с кавычкой (`a".jpg`) сломает заголовок; non-ASCII имена передаются как есть, без `filename*=UTF-8''` (RFC 5987).
**[CONFIRMED]** Range-запросы, ETag, `Cache-Control`, условные запросы **не поддерживаются** — докачка невозможна.

### 8.5. `PATCH /api/v1/files/{id}` (rename)

| Аспект | Значение |
| --- | --- |
| Request | `RenameFileRequest {originalName}` — `@NotBlank @Size(max=255)` |
| Response 200 | обновлённый `FileItemDto` |
| Ошибки | `400` (blank после trim → `IllegalArgumentException`, validation); `404`; `409 CONFLICT` (имя занято, кроме CAMERA); `401` |
| Побочные эффекты | UPDATE `file_item.original_name`. Физическое имя файла **не меняется** |

### 8.6. `POST /api/v1/files/{id}/move`

| Аспект | Значение |
| --- | --- |
| Request | `MoveFileRequest {targetFolderId}` — `@NotNull` |
| Response 200 | обновлённый `FileItemDto` |
| Ошибки | `400` validation; `404` (файл или папка); `409` (конфликт **имени**); `401`; **`500 DATABASE_CONSTRAINT_VIOLATION`** при конфликте **checksum** в целевой папке |
| Побочные эффекты | UPDATE `file_item.folder_id`. Физический файл не перемещается |

**[CONFIRMED][RISK]** Проверяется только имя (`ensureFileNameAvailable`), но не checksum → нарушение `uk_file_item_user_folder_checksum` уходит в `GlobalExceptionHandler.handleDataIntegrityViolation()` и превращается в `500`, а не в `409`.

### 8.7. `POST /api/v1/files/{id}/copy`

| Аспект | Значение |
| --- | --- |
| Request | `CopyFileRequest {targetFolderId?, originalName?}` — `originalName`: `@Size(max=255)` |
| Response 200 | новый `FileItemDto` |
| Ошибки | `400`; `404`; `409 CONFLICT` — «File with this name already exists in folder» **или** «File with this checksum already exists in folder»; `401`; `500` при I/O-ошибке |
| Побочные эффекты БД | INSERT `stored_object` (новый!), INSERT `file_item`, опционально INSERT `file_metadata` (копия) |
| Файловое хранилище | `Files.copy(source, target)` — **новая физическая копия байтов** |

**[CONFIRMED]** Дедупликация намеренно не применяется: комментарий «Copy намеренно не использует dedup: пользователь получает независимый физический объект».
**[CONFIRMED]** Копирование в **ту же** папку всегда даёт `409` (checksum уже есть) — даже с новым именем; подтверждено тестом `copyIntoSameFolderWithNewNameIsRejectedAsChecksumDuplicate`.
**[CONFIRMED]** `capturedAt` копируется из источника, `uploadedAt` = `now()`.
**[CONFIRMED][RISK]** `Files.copy()` без `REPLACE_EXISTING` бросит `FileAlreadyExistsException` при коллизии имени — практически невероятно из-за UUID, но приведёт к `500`.

### 8.8. `DELETE /api/v1/files/{id}`

| Аспект | Значение |
| --- | --- |
| Response | **`204 No Content`** |
| Ошибки | `404`; `401` |
| Побочные эффекты БД | DELETE всех `file_item` на этот `stored_object`, затем DELETE `stored_object` (в транзакции); `file_metadata` каскадом |
| Файловое хранилище | `Files.deleteIfExists()` после commit; **ошибка удаления только логируется** |

**[CONFIRMED]** Hard delete, `deleted_at` не заполняется.

### 8.9. `GET /api/v1/files/checksums`

| Аспект | Значение |
| --- | --- |
| Response 200 | `FileChecksumDto[] {id, originalFilename, checksum}` |
| Ошибки | `401` |
| Побочные эффекты | нет |

**[CONFIRMED][RISK]** Без пагинации и без фильтра по папке — при большой библиотеке ответ неограничен.
**[CONFIRMED]** Возвращает `checksum` из `StoredObject` (JPQL-проекция `f.storedObject.checksum`), а не денормализованный `f.checksum` — на текущий момент значения совпадают.

### 8.10. `POST /api/v1/files/checksums/exists`

| Аспект | Значение |
| --- | --- |
| Request | `ChecksumExistsRequest {folderId, checksums[]}` |
| Validation | `folderId` `@NotNull`; `checksums` `@NotEmpty`, каждый элемент `@NotBlank @Pattern("^[0-9a-fA-F]{64}$")`; batch ≤ `sync.checksum-exists.max-batch-size` (500) — проверяется в сервисе |
| Response 200 | `ChecksumExistsResponse {existing[], missing[]}` |
| Ошибки | `400 VALIDATION_ERROR` (формат) / `400 BAD_REQUEST` (batch больше лимита); `404` (чужая/несуществующая папка); `401` |
| Побочные эффекты | **нет** — endpoint чисто read-only, `FileItem` не создаёт |

**[CONFIRMED]** Нормализация: все checksum приводятся к lowercase, дубликаты схлопываются `LinkedHashSet`, порядок ответа = порядок первого появления во входном списке.
**[CONFIRMED]** Проверка batch-лимита выполняется **до** проверки папки — тест `checkExistingRejectsBatchOverLimitBeforeFolderAndRepositoryCalls` это фиксирует.

---

## 9. Общие DTO ответов

### `FileItemDto`
```json
{
  "id": 42, "folderId": 7, "originalFilename": "photo.jpg",
  "mimeType": "image/jpeg", "size": 123456,
  "checksum": "<64 hex lowercase>", "fileType": "IMAGE",
  "capturedAt": "2026-05-17T10:15:30", "uploadedAt": "2026-05-17T10:16:00",
  "deletedAt": null,
  "metadata": { "width": 4032, "height": 3024, "durationSec": null,
    "cameraMake": "Google", "cameraModel": "Pixel", "lensModel": null,
    "exposureTime": "1/120", "fNumber": 1.8, "iso": 100,
    "focalLength": 4.38, "latitude": null, "longitude": null }
}
```
**[CONFIRMED]** `metadata` целиком `null`, если не извлечено ни одного поля. `deletedAt` всегда `null`. `fNumber` сериализуется именно так благодаря `@JsonProperty("fNumber")`.
**[CONFIRMED]** Даты — `LocalDateTime` без таймзоны, формат ISO-8601 без смещения (`2026-05-17T10:15:30`).

### `FolderDto`
```json
{"id":10,"parentId":1,"name":"Trips","folderType":"USER",
 "createdAt":"...","updatedAt":"..."}
```

### `UserDto`
```json
{"id":1,"email":"user@example.com","displayName":null,"enabled":true,
 "banned":false,"roles":["USER"],"createdAt":"...","lastUpdate":"...","lastLoginAt":"..."}
```

### `ErrorResponse`
```json
{"id":"<uuid>","code":"VALIDATION_ERROR","message":"Validation failed",
 "fieldErrors":{"email":"Email must be valid"}}
```
**[CONFIRMED]** `fieldErrors` присутствует в JSON всегда, со значением `null` вне validation-ошибок (Jackson-конфигурации `NON_NULL` нет; тесты явно проверяют `$.fieldErrors` == null).

---

## 10. Сравнение с существующей документацией

**[CONFIRMED]** Файла `api-contract.md` в проекте **нет**. Есть четыре тематических контракта:

| Файл | Покрывает |
| --- | --- |
| `docs/api-user-contract.md` | root/test, register, login, refresh, logout×3, password reset, profile |
| `docs/api-folder-contract.md` | все 6 folder-endpoint'ов |
| `docs/api-file-contract.md` | все 11 file-endpoint'ов |
| `docs/api-checksum-sync-contract.md` | `POST /files/checksums/exists` |
| `docs/application-overview.md` | обзорная карта реализации |
| `docs/http-tests/*` | smoke-запросы |

Покрытие документацией **полное**: все 36 маппингов упомянуты хотя бы в одном файле. Общее качество документации высокое — она заметно точнее типичной.

---

## 11. API contract inconsistencies

Расхождения между документацией и фактическим кодом:

| # | Область | Документация утверждает | Сервер фактически делает | Severity |
| --- | --- | --- | --- | --- |
| 1 | `POST /files/{id}/move` | `api-file-contract.md`: ошибки — только `400/404/409` | При конфликте **checksum** в целевой папке возвращает **`500 DATABASE_CONSTRAINT_VIOLATION`** (проверки нет, срабатывает БД-constraint) | **HIGH** |
| 2 | Список ошибок files | `api-file-contract.md` §«Ошибки» перечисляет `400/401/404/409/413` | `500` также достижим (см. #1, а также `Files.copy` I/O) | MEDIUM |
| 3 | `docs/http-tests/api-smoke-tests.http` | заявлен как «запросы для всех endpoint'ов, которые сейчас объявлены в контроллерах» | **Не содержит** ни одного из 6 folder-endpoint'ов, а также `POST /files/upload`, `PATCH /files/{id}`, `POST /files/{id}/move`, `POST /files/{id}/copy` | MEDIUM |
| 4 | `docs/http-tests/api-curl-examples.md` | README обещает «аналогичные curl-примеры» | Файл обрывается на заголовке `## RootController` — примеров нет (197 байт) | MEDIUM |
| 5 | `docs/http-tests/README.md` | «Контроллер списка медиафайлов сейчас читает только `page` и `size`» | Читает также `folderId` (добавлен позже, README не обновлён) | LOW |
| 6 | `README.md` §Возможности | «Поддержка контрольных сумм (checksum) для исключения дубликатов» | Дубликаты исключаются только **в пределах папки**; одинаковый файл в разных папках хранится дважды | MEDIUM |
| 7 | `README.md` §План развития | «Поддержка альбомов и папок» — как будущее | Папки уже реализованы полностью; альбомов нет | LOW |
| 8 | `README.md` §БД | «Таблицы: пользователи, токены, запросы email, медиафайлы» | Таблицы `media_file` больше нет; актуальны `folder`, `file_item`, `stored_object`, `file_metadata` | LOW |
| 9 | Password reset flow | `api-user-contract.md` описывает `/reset/page` как «зарезервированный» | Письмо восстановления пароля **ведёт именно на этот 501-endpoint**, то есть flow нерабочий end-to-end | **HIGH** |
| 10 | `GET /auth/register/confirm` | `api-user-contract.md` показывает text-ответ | Совпадает, но это единственный не-JSON endpoint — в общем описании формата ошибок/ответов не оговорено | LOW |
| 11 | Коды статусов создания | контракты указывают `200 OK` для `POST /folders` и upload | Совпадает с кодом, но нарушает REST-конвенцию `201 Created` — стоит зафиксировать для клиента | LOW |
| 12 | `TODO.txt` п.20 | «Не нужно возвращать файл после его загрузки» | Сервер по-прежнему возвращает полный `FileItemDto` | LOW (намерение, не расхождение) |

---

## 12. Наблюдения по консистентности самого API

**[CONFIRMED][INCONSISTENCY]** Внутренние несоответствия дизайна:

| # | Наблюдение |
| --- | --- |
| 1 | Создание ресурсов возвращает `200`, а не `201` — кроме `POST /auth/register` (`201`) |
| 2 | `POST /files` и `POST /files/upload` полностью дублируют друг друга |
| 3 | Rename файла — `PATCH /files/{id}` с полем `originalName`; rename папки — `PATCH /folders/{id}` с полем `name` (разные имена поля для одной операции) |
| 4 | Move реализован как `POST /{id}/move` (не `PATCH`), а rename — как `PATCH` |
| 5 | Простые ответы возвращаются как `Map<String,String>` с ключом `message` — отдельного DTO нет |
| 6 | `folderId` в upload передаётся как `@RequestParam` внутри multipart, а в `checksums/exists` — как поле JSON-тела |
| 7 | Три варианта передачи параметра в auth-flow: JSON-тело (login, refresh, logout), query (`reset/request?email=`, `register/confirm?code=`), JSON (`reset/confirm`) |
| 8 | `GET /files` возвращает `PageResponse` напрямую (не `ResponseEntity`), остальные — `ResponseEntity` |
| 9 | Заглушки `501` возвращают `{"message": ...}`, а не `ErrorResponse` — клиент не может разбирать их единым парсером ошибок |
