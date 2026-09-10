# 01. Effective API Contract (восстановлен по серверному коду)

> **Для аудитора Android-клиента.** Это фактический контракт сервера `photo-cloud-server`, восстановленный по коду, а не по документации. Расхождения с существующими `docs/api-*-contract.md` вынесены в `07-api-contract-differences.md`.
> Base URL: `http(s)://<host>:8080`, префикс всех путей — `/api/v1`.
> Формат: `application/json` (кроме upload — `multipart/form-data`, download — бинарный поток, и `GET /auth/register/confirm` — `text/plain`).
> Даты — `LocalDateTime` **без таймзоны и без смещения**: `2026-05-17T10:15:30`.

---

## 0. Сводная таблица

| Method | Path | Auth | Идемпотентен | Основные ошибки |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/test` | — | ✅ | — |
| GET | `/api/v1/test/auth` | Bearer | ✅ | 401 |
| POST | `/api/v1/auth/register` | — | ❌ | 400, 409 |
| GET | `/api/v1/auth/register/confirm` | — | ❌ | 400 |
| POST | `/api/v1/auth/register/resend` | — | — | **501** |
| POST | `/api/v1/auth/login` | — | ❌ | 400, 401 |
| POST | `/api/v1/auth/refresh-token` | — | ✅ | 400, 401, 403 |
| POST | `/api/v1/auth/logout` | Bearer | ✅ (де-факто) | 400, 401, 403, 404 |
| POST | `/api/v1/auth/logout-all` | Bearer | ✅ | 401 |
| POST | `/api/v1/auth/logout-others` | Bearer | ✅ | 400, 401, 403, 404 |
| POST | `/api/v1/auth/password/reset/request` | — | ❌ | 400 |
| POST | `/api/v1/auth/password/reset/confirm` | — | ❌ | 400 |
| POST | `/api/v1/auth/password/reset/resend` | — | — | **501** |
| GET/POST | `/api/v1/auth/password/reset/page` | — | — | **501** |
| GET | `/api/v1/profile` | Bearer | ✅ | 401 |
| PATCH | `/api/v1/profile` | Bearer | — | **501** |
| GET/PATCH | `/api/v1/profile/settings` | Bearer | — | **501** |
| GET | `/api/v1/folders/root` | Bearer | ✅ | 401 |
| GET | `/api/v1/folders/{id}/children` | Bearer | ✅ | 401, 404 |
| POST | `/api/v1/folders` | Bearer | ❌ | 400, 401, 404, 409 |
| PATCH | `/api/v1/folders/{id}` | Bearer | ✅ | 400, 401, 404, 409 |
| POST | `/api/v1/folders/{id}/move` | Bearer | ✅ | 400, 401, 404, 409 |
| DELETE | `/api/v1/folders/{id}` | Bearer | ❌ | 400, 401, 404 |
| POST | `/api/v1/files` | Bearer | **✅ в пределах папки** | 400, 401, 404, 409, 413 |
| POST | `/api/v1/files/upload` | Bearer | ✅ (дубликат предыдущего) | те же |
| GET | `/api/v1/files` | Bearer | ✅ | 401, 404 |
| GET | `/api/v1/files/{id}` | Bearer | ✅ | 401, 404 |
| GET | `/api/v1/files/{id}/download` | Bearer | ✅ | 401, 404 |
| PATCH | `/api/v1/files/{id}` | Bearer | ✅ | 400, 401, 404, 409 |
| POST | `/api/v1/files/{id}/move` | Bearer | ✅ | 400, 401, 404, 409, **500** |
| POST | `/api/v1/files/{id}/copy` | Bearer | ❌ | 400, 401, 404, 409 |
| DELETE | `/api/v1/files/{id}` | Bearer | ❌ | 401, 404 |
| GET | `/api/v1/files/checksums` | Bearer | ✅ | 401 |
| POST | `/api/v1/files/checksums/exists` | Bearer | ✅ | 400, 401, 404 |

---

## 1. Диагностика

### `GET /api/v1/test`
**Auth:** нет. **Response `200`:** `{"message":"All good! Permit all connection"}`
**Ограничение:** статический ответ, **не проверяет БД и хранилище** — не годится как health-check зависимостей.

### `GET /api/v1/test/auth`
**Auth:** Bearer. **Response `200`:** `{"message":"All good! Authenticated connection. Hello <email>"}`
**Применение:** проверка валидности access token.

---

## 2. Регистрация

### `POST /api/v1/auth/register`
**Auth:** нет.
**Request:**
```json
{"email": "user@example.com", "password": "pass1"}
```
**Валидация:** `email` — not blank, формат email; `password` — not blank, длина **4–20**.
**Response `201 Created`:** `{"message":"Email was sent"}`
**Ошибки:** `400 VALIDATION_ERROR`; `409 CONFLICT` — «Email is already registered».
**Идемпотентность:** нет — повтор даёт `409`.
**Ограничения:**
- аккаунт создаётся **disabled**, войти до подтверждения нельзя;
- сбой отправки письма **не влияет** на ответ — сервер вернёт `201`, даже если письмо не ушло;
- **rate limiting отсутствует**;
- поля `displayName` в контракте нет.

### `GET /api/v1/auth/register/confirm?code=<uuid>`
**Auth:** нет.
**Response `200`:** **`text/plain`** — `User user@example.com was verified`
**Ошибки:** `400 BAD_REGISTRATION_REQUEST` — единый ответ для «код не найден», «уже использован», «истёк», «не того типа».
**Ограничения:** код живёт **3 дня**; повторное использование даёт `400`; **это единственный endpoint, отдающий не-JSON**.

### `POST /api/v1/auth/register/resend`
**Response `501`:** `{"message":"Registration confirmation resend is not implemented yet"}`
**Критично для клиента:** повторно запросить письмо активации **невозможно**. Пользователь, потерявший письмо, не может ни активироваться, ни зарегистрироваться заново (`409`).

---

## 3. Аутентификация

### `POST /api/v1/auth/login`
**Auth:** нет.
**Request:**
```json
{"email": "user@example.com", "password": "pass1"}
```
**Валидация:** `email` — not blank + формат; `password` — not blank, **4–20 символов**.
**Response `200`:**
```json
{"accessToken": "<jwt>", "refreshToken": "<jwt>"}
```
**Ошибки:** `400 VALIDATION_ERROR`; `401 INVALID_CREDENTIALS`.
**Ограничения:**
- `401 INVALID_CREDENTIALS` возвращается **одинаково** при неизвестном email, неверном пароле, неподтверждённом и заблокированном аккаунте — клиент **не может** различить эти случаи и должен показывать общее сообщение;
- каждый вызов создаёт **новый** refresh token; старые не отзываются;
- ограничения на число одновременных сессий нет;
- **валидация `@Size(max=20)` применяется и к login** — пароль длиннее 20 символов будет отклонён с `400` до проверки.

### `POST /api/v1/auth/refresh-token`
**Auth:** **нет** — access token не нужен.
**Request:** `{"refreshToken": "<jwt>"}`
**Response `200`:** `{"accessToken": "<jwt>"}` — **только access token**.
**Ошибки:** `400` (blank); `401 INVALID_REFRESH_TOKEN`; `403 REFRESH_TOKEN_REVOKED`.
**Идемпотентность:** ✅ полная — записей в БД нет, вызывать можно многократно.
**Ограничения, критичные для клиента:**
- **refresh token НЕ ротируется** — клиент продолжает использовать тот же самый весь срок (7 дней);
- новый refresh token получить можно **только через повторный login**;
- `403` — терминально (был logout): требуется полный re-login;
- `401` — тоже терминально: требуется re-login.

### `POST /api/v1/auth/logout`
**Auth:** **Bearer (обязателен)**.
**Request:** `{"refreshToken": "<jwt>"}`
**Response `200`:** `{"message":"Logged out successfully"}`
**Ошибки:** `400`; `401` (нет/истёк access token); `403 REFRESH_TOKEN_OWNERSHIP_ERROR`; `404 REFRESH_TOKEN_NOT_FOUND`.
**Ограничения, критичные для клиента:**
- **требуется валидный access token.** Если access истёк (20 минут), сначала нужен refresh, потом logout — двухшаговый выход;
- отзывается **только** переданный refresh token;
- **access token остаётся валидным до 20 минут после logout** — сервер его не отзывает.

### `POST /api/v1/auth/logout-all`
**Auth:** Bearer. **Тело:** отсутствует.
**Response `200`:** `{"message":"All logged out successfully"}` — отзывает все активные refresh-токены пользователя.

### `POST /api/v1/auth/logout-others`
**Auth:** Bearer.
**Request:** `{"refreshToken": "<текущий refresh, который надо СОХРАНИТЬ>"}`
**Response `200`:** `{"message":"All other logged out successfully"}`
**Важно:** при `403`/`404` **ничего не отзывается** — операция безопасна при ошибке.

---

## 4. Восстановление пароля

### `POST /api/v1/auth/password/reset/request?email=<email>`
**Auth:** нет. **Параметр передаётся в query, не в теле.**
**Response `200`:** `{"message":"Email was sent"}`
**Ошибки:** `400 VALIDATION_ERROR` (blank); `400 BAD_REQUEST` — «User \<email\> not found».
**Ограничения:** формат email **не проверяется**; несуществующий адрес даёт `400` (раскрытие существования аккаунта); rate limiting отсутствует.

### `POST /api/v1/auth/password/reset/confirm`
**Auth:** нет.
**Request:**
```json
{"password": "newPass1", "code": "5761d001-0660-4052-9945-b2a83103a692"}
```
**Валидация:** `password` — not blank, 4–20; `code` — not blank.
**Response `200`:** `{"message":"Password was reset"}`
**Ошибки:** `400 VALIDATION_ERROR`; `400 BAD_REGISTRATION_REQUEST`.
**Критично для клиента:**
- **смена пароля НЕ отзывает refresh-токены** — после сброса старые сессии продолжают работать;
- письмо ведёт на `/auth/password/reset/page`, который возвращает **`501`**. Клиент должен либо перехватывать deep link, либо просить пользователя скопировать `code` из URL вручную;
- старый query-параметрический контракт (`?password=&code=`) **не поддерживается** — только JSON-тело.

### Нереализованные (все `501`)
`POST /auth/password/reset/resend`, `GET /auth/password/reset/page`, `POST /auth/password/reset/page`.

---

## 5. Профиль

### `GET /api/v1/profile`
**Auth:** Bearer.
**Response `200`:**
```json
{
  "id": 1, "email": "user@example.com", "displayName": null,
  "enabled": true, "banned": false, "roles": ["USER"],
  "createdAt": "2026-05-17T10:15:30",
  "lastUpdate": "2026-05-17T10:15:30",
  "lastLoginAt": "2026-05-17T10:15:30"
}
```
**Ограничения:** `displayName` может быть `null` (при регистрации не задаётся); `lastLoginAt` — `null` до первого входа; пароль не возвращается.

### Нереализованные (все `501`, требуют Bearer)
`PATCH /profile`, `GET /profile/settings`, `PATCH /profile/settings`.

---

## 6. Папки

Все требуют Bearer. Все возвращают `FolderDto`:

```json
{"id": 10, "parentId": 1, "name": "Trips", "folderType": "USER",
 "createdAt": "2026-05-17T10:15:30", "updatedAt": "2026-05-17T10:15:30"}
```

`folderType` ∈ `ROOT` | `CAMERA` | `FILES` | `USER`. У `ROOT` `parentId = null`.

### `GET /api/v1/folders/root`
**Response `200`:** `FolderDto` (`name` = `"root"`).
**Побочный эффект:** если ROOT ещё нет — **создаёт его**. Вызов идемпотентен.
**Применение:** первый запрос клиента после login — нужен, чтобы получить `folderId` для `checksums/exists`.

### `GET /api/v1/folders/{id}/children`
**Response `200`:** массив `FolderDto` — **только прямые потомки**, отсортированные по `lower(name), id`.
**Ошибки:** `404` (нет или чужая).
**Ограничения:** рекурсивного дерева нет — для полного дерева нужно N запросов; файлы в ответ не входят.

### `POST /api/v1/folders`
**Request:** `{"parentId": 1, "name": "Documents"}` — `parentId` опционален (`null` → ROOT); `name` — not blank, ≤255.
**Response:** **`200 OK`** (не `201`), `FolderDto` с `folderType = "USER"`.
**Ошибки:** `400` (blank/длинное имя после trim; попытка создать внутри `CAMERA`/`FILES`); `404`; `409` (имя занято без учёта регистра; имена `Camera` и `Files` зарезервированы в ROOT).

### `PATCH /api/v1/folders/{id}`
**Request:** `{"name": "Archive"}` — обратите внимание: поле называется **`name`** (у файлов — `originalName`).
**Ошибки:** `400` (системная папка); `404`; `409`.

### `POST /api/v1/folders/{id}/move`
**Request:** `{"targetParentId": 12}` — **обязателен**; чтобы переместить в корень, нужно явно указать id ROOT.
**Ошибки:** `400` (системная папка; target = сама папка; target — потомок; target = `CAMERA`/`FILES`); `404`; `409`.

### `DELETE /api/v1/folders/{id}`
**Response:** `204 No Content`, без тела.
**Ошибки:** `400` (системная папка; есть подпапки; есть файлы); `404`.
**Ограничение:** рекурсивное удаление **не поддерживается** — клиент должен очистить папку сам.

---

## 7. Файлы

`FileItemDto` — единый DTO для всех операций:

```json
{
  "id": 42,
  "folderId": 7,
  "originalFilename": "photo.jpg",
  "mimeType": "image/jpeg",
  "size": 123456,
  "checksum": "aaaa...(64 hex lowercase)",
  "fileType": "IMAGE",
  "capturedAt": "2026-05-17T10:15:30",
  "uploadedAt": "2026-05-17T10:16:00",
  "deletedAt": null,
  "metadata": {
    "width": 4032, "height": 3024, "durationSec": null,
    "cameraMake": "Google", "cameraModel": "Pixel", "lensModel": null,
    "exposureTime": "1/120", "fNumber": 1.8, "iso": 100,
    "focalLength": 4.38, "latitude": null, "longitude": null
  }
}
```

Подробная семантика полей — в `04-media-data-contract.md`.

### `POST /api/v1/files` — Upload
### `POST /api/v1/files/upload` — тот же самый

**Auth:** Bearer. **Content-Type:** `multipart/form-data`.

| Параметр | Тип | Обязателен |
| --- | --- | --- |
| `file` | часть multipart | ✅ |
| `folderId` | параметр формы (`Long`) | ❌ |

**Response:** **`200 OK`** (не `201`), `FileItemDto`.
**Ошибки:** `400` (пустой файл; нет части `file`); `401`; `404` (чужой/несуществующий `folderId`); `409 CONFLICT` (имя занято, кроме `CAMERA`); `413 FILE_TOO_LARGE`.

**Идемпотентность: ✅ в пределах папки.** Повторная загрузка тех же байтов в ту же папку возвращает `200` с **уже существующей** записью, не создавая дубликата.

**Ограничения, критичные для клиента:**
- **checksum клиента не принимается** — сервер считает SHA-256 сам;
- **`Content-Type` части multipart игнорируется** — MIME определяется по содержимому (Apache Tika);
- лимиты: `110MB` (multipart) и `104857600` байт ≈ 100 MiB (стриминг);
- без `folderId` файл попадает в `Camera` (IMAGE/VIDEO) или `Files` (остальное) — папки создаются автоматически;
- при загрузке дубля возвращается **старое** `originalFilename`, даже если клиент прислал другое имя;
- **загрузка одним запросом целиком.** Chunked/resumable upload не поддерживается — прерванная загрузка начинается заново;
- множественная загрузка (несколько файлов за запрос) не поддерживается.

### `GET /api/v1/files?page=0&size=10&folderId=7`
**Response `200`:**
```json
{"items": [ /* FileItemDto */ ], "page": 0, "size": 10,
 "totalElements": 1, "totalPages": 1, "hasNext": false, "hasPrevious": false}
```
**Ошибки:** `401`; `404` (чужой `folderId`).
**Ограничения:**
- сортировка **фиксирована**: `capturedAt DESC, uploadedAt DESC, id DESC`; клиент изменить её не может;
- без `folderId` возвращаются файлы **всех** папок; с `folderId` — **только прямые** файлы папки (без вложенных);
- фильтров по типу и датам нет;
- **верхняя граница `size` не задана** — сервер примет любое значение (запрашивать большие страницы не следует).

### `GET /api/v1/files/{id}`
**Response `200`:** `FileItemDto`. **Ошибки:** `404 FILE_ITEM_NOT_FOUND`; `401`.
**Важно:** возвращает `200` даже если **физический файл на сервере отсутствует** — наличие на диске не проверяется. Отсутствие обнаружится только при скачивании.

### `GET /api/v1/files/{id}/download`
**Response `200`:** бинарный поток.
Заголовки: `Content-Type: <mimeType>`; `Content-Disposition: attachment; filename="<originalFilename>"`.
**Ошибки:** `404 FILE_ITEM_NOT_FOUND` — и когда записи нет/чужая, **и когда физический файл отсутствует**.
**Ограничения:**
- **HTTP Range не поддерживается** — докачка невозможна, прерванное скачивание начинается заново;
- `ETag`, `Last-Modified`, `Cache-Control` не выставляются — условные запросы бесполезны;
- имя в `Content-Disposition` **не экранировано**: кавычка в имени сломает заголовок, non-ASCII передаётся без `filename*=UTF-8''`. Клиенту надёжнее брать имя из `FileItemDto.originalFilename`, а не из заголовка.

### `PATCH /api/v1/files/{id}` — Rename
**Request:** `{"originalName": "new-name.jpg"}` — поле называется **`originalName`** (в ответе то же значение приходит как `originalFilename`).
**Валидация:** not blank, ≤255.
**Response `200`:** обновлённый `FileItemDto`.
**Ошибки:** `400`; `404`; `409` (имя занято, **кроме папки `CAMERA`**).
**Идемпотентность:** ✅ — повтор с тем же именем не даёт `409`.

### `POST /api/v1/files/{id}/move`
**Request:** `{"targetFolderId": 12}` — обязателен.
**Response `200`:** обновлённый `FileItemDto` с новым `folderId`.
**Ошибки:** `400`; `404`; `409` (конфликт **имени**); **`500 DATABASE_CONSTRAINT_VIOLATION`** — если в целевой папке уже есть файл с **тем же checksum**.

> ⚠️ **Известный дефект сервера.** Конфликт checksum при `move` возвращает `500`, а не `409`. Это **не временный сбой** — повторять запрос бесполезно. Клиент должен трактовать `500` с `code = "DATABASE_CONSTRAINT_VIOLATION"` как терминальный конфликт и **не включать его в общую retry-политику для 5xx**.

### `POST /api/v1/files/{id}/copy`
**Request:** `{"targetFolderId": 12, "originalName": "copy.jpg"}` — оба поля опциональны.
**Response `200`:** **новый** `FileItemDto`.
**Ошибки:** `400`; `404`; `409` — «File with this name already exists in folder» **или** «File with this checksum already exists in folder».
**Ограничения:**
- копирование **в ту же папку всегда даёт `409`** (даже с новым именем) — checksum уже присутствует;
- сервер создаёт **независимую физическую копию** байтов;
- метаданные копируются; `capturedAt` берётся у источника, `uploadedAt` — текущее время.

### `DELETE /api/v1/files/{id}`
**Response:** `204 No Content`, без тела.
**Ошибки:** `404`; `401`.
**Ограничения:**
- **hard delete** — корзины нет, восстановление невозможно;
- `deletedAt` при этом **не заполняется** (запись просто исчезает);
- не идемпотентен — повтор даёт `404`.

### `GET /api/v1/files/checksums`
**Response `200`:**
```json
[{"id": 42, "originalFilename": "photo.jpg", "checksum": "aaaa..."}]
```
**Ограничения:** **без пагинации и без фильтра по папке** — возвращает все файлы пользователя одним массивом. При большой библиотеке ответ может быть очень большим. Для синхронизации предпочтительнее `POST /files/checksums/exists`.

### `POST /api/v1/files/checksums/exists`
**Request:**
```json
{"folderId": 123, "checksums": ["aaaa...", "bbbb..."]}
```
**Валидация:** `folderId` — **обязателен**; `checksums` — непустой массив, каждый элемент — ровно 64 hex-символа (`^[0-9a-fA-F]{64}$`); максимум **500** элементов на запрос.
**Response `200`:**
```json
{"existing": ["aaaa..."], "missing": ["bbbb..."]}
```
**Ошибки:** `400 VALIDATION_ERROR` (формат) / `400 BAD_REQUEST` («Checksum batch size must be at most 500»); `404` (чужая/несуществующая папка); `401`.
**Идемпотентность:** ✅ полная — read-only, `FileItem` не создаёт.
**Семантика:** `existing` = «в **этой папке** уже есть файл с таким checksum». Тот же checksum в другой папке → `missing`.
**Гарантии:** вход нормализуется в lowercase; дубликаты во входном списке схлопываются; порядок ответа = порядок первого появления во входном списке; ответ содержит **lowercase**.

Подробности — в `05-sync-upload-contract.md`.

---

## 8. Общие правила

**Заголовок авторизации:** `Authorization: Bearer <accessToken>` — регистр префикса значим (`Bearer ` с пробелом).

**Формат ошибок** (для всех, кроме `501`-заглушек и необработанных `500`):
```json
{"id": "<uuid>", "code": "<ERROR_CODE>", "message": "<текст>", "fieldErrors": null}
```
Поле `fieldErrors` присутствует всегда; заполняется только при `VALIDATION_ERROR`. Полный перечень — в `03-error-contract.md`.

**Доступ к чужим объектам** всегда даёт `404`, а не `403` — сервер намеренно не подтверждает существование чужих ресурсов.

**CORS не настроен** — актуально только для веб-клиентов; Android/OkHttp это не затрагивает.

**Ограничения, которых нет:** rate limiting, квоты на объём, ограничения типов файлов, `Idempotency-Key`, версионирование ресурсов (`ETag`/`If-Match`).
