# Verified Current API Specification

Базовый путь `/api/v1`; context-path `/`, configured port 8080. Здесь перечислены все 36 собственных method/path mappings. Семь из них — STUB с 501; остальные имеют реализацию. Наличие реализованного endpoint не означает полноту более широкого workflow: email reset page и sync остаются PARTIAL.

## Общий контракт

`public` не требует Authorization. `access` требует `Authorization: Bearer <ACCESS JWT>` с точным префиксом `Bearer `. Все protected routes могут вернуть 401 UNAUTHORIZED; security denied handler предусматривает 403 FORBIDDEN. Invalid/expired Bearer способен прервать и public request до controller. Подробности — [06](06-auth-security.md).

Path ID и folder ID — Java Long / JSON integer. Page/size — integer. JSON requests валидируются Bean Validation; body отсутствует/повреждён → 400 BAD_REQUEST. Domain/validation ошибки, перечисленные ниже, используют ErrorResponse; не все framework/IO ошибки имеют этот формат. Missing multipart file, conversion и unsupported content type не имеют собственного стабильного JSON-контракта приложения. Для STUB ответ — `{message:string}`, без ErrorResponse. Общие ошибки из [10](10-errors-and-recovery.md) применяются ко всем строкам таблиц и не повторяются полностью.

Имя поля rename request — `originalName`, а response — `originalFilename`. JSON dates — LocalDateTime без offset; отсутствие видео metadata не означает failed upload. Существующие file endpoints принимают logical FileItem.id, а не StoredObject.id.

## Индекс endpoints

| Method | Path | Status |
| --- | --- | --- |
| GET | `/api/v1/test` | IMPLEMENTED |
| GET | `/api/v1/test/auth` | IMPLEMENTED |
| POST | `/api/v1/auth/register` | IMPLEMENTED |
| GET | `/api/v1/auth/register/confirm` | IMPLEMENTED |
| POST | `/api/v1/auth/register/resend` | STUB |
| POST | `/api/v1/auth/login` | IMPLEMENTED |
| POST | `/api/v1/auth/refresh-token` | IMPLEMENTED |
| POST | `/api/v1/auth/logout` | IMPLEMENTED |
| POST | `/api/v1/auth/logout-all` | IMPLEMENTED |
| POST | `/api/v1/auth/logout-others` | IMPLEMENTED |
| POST | `/api/v1/auth/password/reset/request` | IMPLEMENTED |
| POST | `/api/v1/auth/password/reset/confirm` | IMPLEMENTED |
| POST | `/api/v1/auth/password/reset/resend` | STUB |
| GET | `/api/v1/auth/password/reset/page` | STUB |
| POST | `/api/v1/auth/password/reset/page` | STUB |
| GET | `/api/v1/profile` | IMPLEMENTED |
| PATCH | `/api/v1/profile` | STUB |
| GET | `/api/v1/profile/settings` | STUB |
| PATCH | `/api/v1/profile/settings` | STUB |
| GET | `/api/v1/folders/root` | IMPLEMENTED |
| GET | `/api/v1/folders/{id}/children` | IMPLEMENTED |
| POST | `/api/v1/folders` | IMPLEMENTED |
| PATCH | `/api/v1/folders/{id}` | IMPLEMENTED |
| POST | `/api/v1/folders/{id}/move` | IMPLEMENTED |
| DELETE | `/api/v1/folders/{id}` | IMPLEMENTED |
| POST | `/api/v1/files` | IMPLEMENTED |
| POST | `/api/v1/files/upload` | IMPLEMENTED |
| GET | `/api/v1/files` | IMPLEMENTED |
| GET | `/api/v1/files/{id}` | IMPLEMENTED |
| GET | `/api/v1/files/{id}/download` | IMPLEMENTED |
| PATCH | `/api/v1/files/{id}` | IMPLEMENTED |
| POST | `/api/v1/files/{id}/move` | IMPLEMENTED |
| POST | `/api/v1/files/{id}/copy` | IMPLEMENTED |
| DELETE | `/api/v1/files/{id}` | IMPLEMENTED |
| GET | `/api/v1/files/checksums` | IMPLEMENTED |
| POST | `/api/v1/files/checksums/exists` | IMPLEMENTED |

## Операции

### GET /api/v1/test

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет параметров/тела. |
| Response / status | 200 JSON {message: "All good! Permit all connection"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | БД/FS: нет. |

### GET /api/v1/test/auth

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров/тела. |
| Response / status | 200 JSON {message: "All good! Authenticated connection. Hello &lt;email&gt;"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | Principal из БД; записей/FS нет. |

### POST /api/v1/auth/register

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON RegisterRequest: email string @NotBlank @Email; password string @NotBlank @Size(4..20). displayName не объявлен. Email длиной >128 не отвергается DTO-лимитом. |
| Response / status | 201 JSON {message: "Email was sent"}. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 409 CONFLICT email; DB constraint 500 при гонке/длине; SMTP/runtime не имеют единого ErrorResponse. |
| DB / filesystem / side effects | users + user_roles; email_requests ACTIVATE; синхронный SMTP. Общей транзакции нет. |

### GET /api/v1/auth/register/confirm

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Query code string обязателен; @NotBlank нет. |
| Response / status | 200 текст User &lt;email&gt; was verified. |
| Ошибки | 400 BAD_REGISTRATION_REQUEST при missing DB code/used/expired/wrong type; missing query →400 BAD_REQUEST. |
| DB / filesystem / side effects | Транзакция: users.enabled=true, email_requests.used=true; FS нет. |

### POST /api/v1/auth/register/resend

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров. |
| Response / status | 501 JSON {message: "Registration confirmation resend is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет DB/FS/SMTP. |

### POST /api/v1/auth/login

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON LoginRequest: email @NotBlank @Email; password @NotBlank, length 4..20. |
| Response / status | 200 LoginResponse {accessToken:string, refreshToken:string}. Нет expiresIn/tokenType. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_CREDENTIALS для unknown email, bad password, disabled/banned. |
| DB / filesystem / side effects | users.lastLoginAt, lastUpdate; INSERT refresh_token; FS нет. |

### POST /api/v1/auth/refresh-token

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON RefreshRequest {refreshToken:string @NotBlank}. Refresh token принимается из JSON body (`refreshToken`). Endpoint public и не требует Authorization. Если клиент передаёт malformed/expired Bearer, JWT filter способен вернуть 401 до входа в controller. |
| Response / status | 200 RefreshResponse {accessToken:string}. Refresh остаётся прежним. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_REFRESH_TOKEN unknown/malformed/expired/wrong type/subject; 403 REFRESH_TOKEN_REVOKED; invalid Authorization →401 UNAUTHORIZED до controller. |
| DB / filesystem / side effects | Чтение refresh_token и users; новых DB rows/FS нет. |

### POST /api/v1/auth/logout

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON LogoutRequest {refreshToken:string @NotBlank}; token должен принадлежать principal. |
| Response / status | 200 JSON {message: "Logged out successfully"}. |
| Ошибки | 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR. |
| DB / filesystem / side effects | revoked=true, revokedAt=now одной строки; access не отзывается. |

### POST /api/v1/auth/logout-all

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет тела/параметров. |
| Response / status | 200 JSON {message: "All logged out successfully"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | Все revoked=false по userName → revoked=true; revokedAt не меняется. |

### POST /api/v1/auth/logout-others

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON LogoutRequest {refreshToken:string @NotBlank}; существующий token текущего user. Его expiry/revoked не проверяются. |
| Response / status | 200 JSON {message: "All other logged out successfully"}. |
| Ошибки | 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR. |
| DB / filesystem / side effects | Все неотозванные tokens того же user кроме переданного → revoked=true; revokedAt не меняется. |

### POST /api/v1/auth/password/reset/request

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Обязательный query email string @NotBlank; @Email здесь отсутствует. JSON email не является объявленным контрактом. |
| Response / status | 200 JSON {message: "Email was sent"}. |
| Ошибки | 400 VALIDATION_ERROR blank; 400 BAD_REQUEST missing query/unknown user; SMTP/runtime нестандартизованы. |
| DB / filesystem / side effects | Создаёт PASSWORD_RESET email_request и синхронно посылает письмо; FS нет. |

### POST /api/v1/auth/password/reset/confirm

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON PasswordResetConfirmRequest: password @NotBlank size4..20; code @NotBlank. Legacy query-only не принимается. |
| Response / status | 200 JSON {message: "Password was reset"}. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code. |
| DB / filesystem / side effects | Транзакция password hash + used текущего кода и reset-кодов user за последние 3 дня. Refresh/access не отзываются. |

### POST /api/v1/auth/password/reset/resend

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров. |
| Response / status | 501 JSON {message: "Password reset resend is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |

### GET /api/v1/auth/password/reset/page

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Query code string обязателен, иначе 400; code не проверяется по БД. |
| Response / status | 501 JSON {message: "Password reset page is not implemented yet"}. HTML не возвращается. |
| Ошибки | 400 BAD_REQUEST missing query; 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет; сюда ведёт reset email. |

### POST /api/v1/auth/password/reset/page

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров; form не обрабатывается. |
| Response / status | 501 JSON {message: "Password reset page submit is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |

### GET /api/v1/profile

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 200 UserDto: id,email,displayName,enabled,banned,roles,createdAt,lastUpdate,lastLoginAt. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | DTO principal из БД; записей/FS нет. |

### PATCH /api/v1/profile

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет request DTO; присланные поля не обрабатываются. |
| Response / status | 501 JSON {message: "Profile update is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |

### GET /api/v1/profile/settings

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 501 JSON {message: "Profile settings are not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет; settings entity отсутствует. |

### PATCH /api/v1/profile/settings

**Status:** STUB

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет DTO; присланные настройки не применяются. |
| Response / status | 501 JSON {message: "Profile settings update is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |

### GET /api/v1/folders/root

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 200 FolderDto; parentId=null, name=root, folderType=ROOT. |
| Ошибки | DB race может привести к DB/runtime ошибке; нет обещания универсального 409. |
| DB / filesystem / side effects | Ленивый INSERT folder ROOT если отсутствует; FS нет. |

### GET /api/v1/folders/{id}/children

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id: Long; обязательный; без pagination/recursive/query filters. |
| Response / status | 200 JSON array FolderDto, прямые потомки, sort lower(name),id. |
| Ошибки | 404 NOT_FOUND missing/foreign parent. |
| DB / filesystem / side effects | Чтение folder; FS нет. |

### POST /api/v1/folders

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON CreateFolderRequest: parentId optional Long (null→ROOT); name @NotBlank @Size(max255); service trim. Parent ROOT/USER. |
| Response / status | 200 FolderDto новой USER-папки. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST system leaf/blank/length; 404 NOT_FOUND parent; 409 CONFLICT occupied/reserved name; DB race может дать500. |
| DB / filesystem / side effects | INSERT folder, возможно ROOT; FS нет. |

### PATCH /api/v1/folders/{id}

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. JSON RenameFolderRequest {name:string @NotBlank @Size(max255)}; trim. Только USER. |
| Response / status | 200 FolderDto. |
| Ошибки | 400 BAD_REQUEST system folder / VALIDATION_ERROR; 404 NOT_FOUND; 409 CONFLICT occupied/reserved. |
| DB / filesystem / side effects | UPDATE folder.name, updatedAt; FS нет. |

### POST /api/v1/folders/{id}/move

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON MoveFolderRequest {targetParentId:Long @NotNull}; target ROOT/USER. |
| Response / status | 200 FolderDto. |
| Ошибки | 400 BAD_REQUEST system/self/descendant/system target; 404 NOT_FOUND; 409 CONFLICT names/reserved; validation400. |
| DB / filesystem / side effects | UPDATE parent_id/updatedAt; FS нет. |

### DELETE /api/v1/folders/{id}

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; только USER без children и files. |
| Response / status | 204 без тела. |
| Ошибки | 400 BAD_REQUEST system/nonempty; 404 NOT_FOUND; DB race500 возможен. |
| DB / filesystem / side effects | DELETE folder; FS не затрагивается. |

### POST /api/v1/files

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка. |
| Response / status | 200 FileItemDto и при создании, и при duplicate; нет Location/upload-status. |
| Ошибки | Empty file → application 400 BAD_REQUEST; отсутствующая multipart-часть `file`, нечисловой `folderId` и другие binding/conversion cases — framework-ответ без гарантированного собственного ErrorResponse приложения; точный wire-contract — OPEN-SRV-030; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler. |
| DB / filesystem / side effects | Temp write + SHA-256/size/MIME/metadata → folder resolve → duplicate/name check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate возвращает старый DTO без новых объектов; folders могут быть созданы до final transaction. |

### POST /api/v1/files/upload

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка. |
| Response / status | 200 FileItemDto; тот же pipeline, что POST /files. |
| Ошибки | Empty file → application 400 BAD_REQUEST; отсутствующая multipart-часть `file`, нечисловой `folderId` и другие binding/conversion cases — framework-ответ без гарантированного собственного ErrorResponse приложения; точный wire-contract — OPEN-SRV-030; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler. |
| DB / filesystem / side effects | Temp write + SHA-256/size/MIME/metadata → folder resolve → duplicate/name check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate возвращает старый DTO без новых объектов; folders могут быть созданы до final transaction. |

### GET /api/v1/files

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Query page int default0 >=0, size int default10 >0 (PageRequest); верхний предел size не установлен; optional folderId Long, только прямые файлы. type/from/to/sort не объявлены. |
| Response / status | 200 PageResponse&lt;FileItemDto&gt; {items,page,size,totalElements,totalPages,hasNext,hasPrevious}. Sort capturedAt DESC,uploadedAt DESC,id DESC. |
| Ошибки | 400 BAD_REQUEST negative page/size0; type-conversion400 форма не задана custom handler;404 NOT_FOUND folder. |
| DB / filesystem / side effects | Read DB; нет FS availability check. |

### GET /api/v1/files/{id}

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. |
| Response / status | 200 FileItemDto; physical path/filename/StoredObject ID не раскрываются. |
| Ошибки | 404 FILE_ITEM_NOT_FOUND missing/foreign file. |
| DB / filesystem / side effects | DB only; physical bytes не проверяются. |

### GET /api/v1/files/{id}/download

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. Пользовательское Range/ETag поведение не реализовано явно и не закреплено тестами. |
| Response / status | 200 Resource/raw bytes; Content-Type = stored detectedMimeType; Content-Disposition: attachment; filename="&lt;originalName&gt;". |
| Ошибки | 404 FILE_ITEM_NOT_FOUND missing/foreign/unreadable or absent physical; IOException без общего custom500; invalid DB MIME/path может дать400 BAD_REQUEST. |
| DB / filesystem / side effects | Read DB + filesystem; response обёрнут HttpLoggingFilter cache. |

### PATCH /api/v1/files/{id}

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON RenameFileRequest {originalName:string @NotBlank @Size(max255)}. Trim + sanitize. Request originalName отличается от response originalFilename. |
| Response / status | 200 FileItemDto. |
| Ошибки | 400 validation/BAD_REQUEST;404 FILE_ITEM_NOT_FOUND;409 CONFLICT name outside CAMERA; race DB errors500. |
| DB / filesystem / side effects | UPDATE FileItem.originalName; physical filename неизменен. |

### POST /api/v1/files/{id}/move

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON MoveFileRequest {targetFolderId:Long @NotNull}. Любая своя папка, включая системную. |
| Response / status | 200 FileItemDto. |
| Ошибки | 400 validation;404 FILE_ITEM_NOT_FOUND/NOT_FOUND folder;409 CONFLICT name outside CAMERA;500 DATABASE_CONSTRAINT_VIOLATION при checksum conflict если name-check пропущен/прошёл. |
| DB / filesystem / side effects | UPDATE FileItem.folder; физический файл не перемещается; checksum precheck в move отсутствует. |

### POST /api/v1/files/{id}/copy

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON CopyFileRequest обязателен, {} допустим. targetFolderId optional(null→source folder); originalName optional @Size(max255), null/blank→source name; иначе trim+sanitize. |
| Response / status | 200 новый FileItemDto. |
| Ошибки | 400 body/validation;404 file/folder;409 CONFLICT name/checksum;500 constraint race; missing source bytes→IOException без специального404 handler. |
| DB / filesystem / side effects | Files.copy → transaction new StoredObject/FileItem/metadata; uploadedAt новый, capturedAt прежний. Новые UUID/path; checksum/type/size/extension наследуются, не пересчитываются. |

### DELETE /api/v1/files/{id}

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. |
| Response / status | 204 без тела. |
| Ошибки | 404 FILE_ITEM_NOT_FOUND; DB errors500; IOException удаления bytes только логируется и не отменяет204. |
| DB / filesystem / side effects | Owner: transaction delete all FileItem references + StoredObject, metadata cascade; после commit Files.deleteIfExists. Non-owner StoredObject: только свой FileItem. |

### GET /api/v1/files/checksums

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет query/pagination/filter параметров. |
| Response / status | 200 массив FileChecksumDto {id,originalFilename,checksum}; все FileItem user; folderId отсутствует, порядок не задан. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | DB projection через StoredObject.checksum, без filesystem. |

### POST /api/v1/files/checksums/exists

**Status:** IMPLEMENTED

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON ChecksumExistsRequest: folderId Long @NotNull; checksums @NotEmpty List; каждый @NotBlank и 64 hex regex. Max raw list size500 до dedup; без trim. Lowercase Locale.ROOT и dedup первого появления. |
| Response / status | 200 ChecksumExistsResponse {existing:string[],missing:string[]}; каждый hash один раз в своей partition, порядок первого появления; нет file IDs. |
| Ошибки | 400 VALIDATION_ERROR null/blank/invalid;400 BAD_REQUEST batch>max;404 NOT_FOUND folder;401. |
| DB / filesystem / side effects | Read file_item.checksum только указанной папки/user; без physical check, lock/reservation/DB writes. |

## Wire DTO dictionary

Обязательные поля запросов не допускают null; optional поля допускают отсутствие/null. `NotBlank` отвергает пустые и состоящие из пробелов строки. Неопределённые request fields не являются поддерживаемыми функциями: например, upload не принимает авторитетные client checksum/capturedAt/processing status.

| DTO | Поля и validation |
| --- | --- |
| RegisterRequest | email:string NotBlank Email; password:string NotBlank, length 4..20 |
| LoginRequest | email:string NotBlank Email; password:string NotBlank, length 4..20 |
| RefreshRequest | refreshToken:string NotBlank |
| LogoutRequest | refreshToken:string NotBlank |
| PasswordResetConfirmRequest | password:string NotBlank, length 4..20; code:string NotBlank |
| CreateFolderRequest | parentId:Long optional, null → ROOT; name:string NotBlank max255 |
| RenameFolderRequest | name:string NotBlank max255 |
| MoveFolderRequest | targetParentId:Long NotNull |
| RenameFileRequest | originalName:string NotBlank max255 |
| MoveFileRequest | targetFolderId:Long NotNull |
| CopyFileRequest | targetFolderId:Long optional, null → source folder; originalName:string optional max255, blank/null → source name; body `{}` допустим |
| ChecksumExistsRequest | folderId:Long NotNull; checksums:List NotEmpty, каждый NotBlank и regex `^[0-9a-fA-F]{64}$`; raw count ≤500 |

Password reset request передаёт email обязательным query-параметром с NotBlank, без Email validation. Register confirm и GET reset page передают обязательный query code без NotBlank. STUB profile/settings/resend/POST reset page не определяют request DTO. Multipart upload содержит обязательный file и optional folderId.

| Response DTO | Поля и типы |
| --- | --- |
| LoginResponse | accessToken:string, refreshToken:string; нет expiresIn/tokenType |
| RefreshResponse | accessToken:string; refresh не возвращается заново |
| UserDto | id:Long, email:string, displayName:string/null, enabled:boolean, banned:boolean, roles:Role[], createdAt:LocalDateTime/null, lastUpdate:LocalDateTime/null, lastLoginAt:LocalDateTime/null; password отсутствует |
| FolderDto | id:Long, parentId:Long/null (ROOT), name:string, folderType:FolderType, createdAt:LocalDateTime, updatedAt:LocalDateTime |
| FileItemDto | id:Long, folderId:Long, originalFilename:string, mimeType:string, size:Long (bytes), checksum:string, fileType:FileType, capturedAt:LocalDateTime, uploadedAt:LocalDateTime, deletedAt:LocalDateTime/null, metadata:FileMetadataDto/null |
| FileMetadataDto | width:Integer/null, height:Integer/null, durationSec:Integer/null, cameraMake:string/null, cameraModel:string/null, lensModel:string/null, exposureTime:string/null, fNumber:number/null, iso:Integer/null, focalLength:number/null, latitude:number/null, longitude:number/null |
| PageResponse&lt;FileItemDto&gt; | items:FileItemDto[], page:int, size:int, totalElements:long, totalPages:int, hasNext:boolean, hasPrevious:boolean |
| FileChecksumDto | id:Long (FileItem), originalFilename:string, checksum:string; folderId отсутствует |
| ChecksumExistsResponse | existing:string[], missing:string[]; lowercase unique hashes, порядок первого появления в каждой группе; file IDs отсутствуют |
| ErrorResponse | id:UUID string, code:string, message:string, fieldErrors:map&lt;string,string&gt;/null |

FileType = IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER; FolderType = ROOT, CAMERA, FILES, USER; Role = USER, ADMIN. `fNumber` имеет именно такое wire-имя. Числовая точность metadata ограничивается SQL: fNumber/focalLength 4 десятичных знака, latitude/longitude 7; фиксированный текстовый масштаб JSON не обещается. durationSec текущий extractor не заполняет. capturedAt хранится в FileItem, а не в metadata.

## Семантика файловых конфликтов

| Ситуация | Результат |
| --- | --- |
| Upload того же user/folder/checksum, в том числе с другим именем | 200 существующий DTO; имя, metadata, даты прежние |
| Upload того же checksum в другую папку | Новый FileItem + StoredObject + bytes, 200 |
| Новое содержимое с занятым именем вне CAMERA | 409 CONFLICT |
| Новое содержимое с занятым именем в CAMERA | Новая запись, 200 |
| Copy в папку с тем же checksum | 409; в исходную папку конфликтует и с новым именем |
| Move в папку с тем же checksum | Сначала возможен name conflict 409; если name check прошёл/пропущен, DB constraint →500 DATABASE_CONSTRAINT_VIOLATION |
| Repeated delete после успеха | 404; прежняя FS cleanup не повторяется |

## Документация API в repository

Repository API docs и `.http` examples не являются authoritative source текущей спецификации. Основные DTO и happy paths в значительной части совпадают; известные расхождения зафиксированы в Audit A/B:

- Checksum conflict при move способен привести к `500 DATABASE_CONSTRAINT_VIOLATION`, что repository contract отражает не полностью.
- Manual smoke/examples не покрывают весь текущий API.
- Комментарий о порядке delete DB/FS расходится с verified behaviour: DB delete завершается до удаления bytes.
- Комментарий о password reset/revoke не соответствует текущему token lifecycle: reset не отзывает access/refresh.
- `POST /files` обозначается как compatibility/legacy alias; endpoint реализован.
- `GET /files/checksums` в документации может называться старым; endpoint реализован.

## Framework surface — OPEN

Приложение возвращает `ResponseEntity<Resource>` для download и само задаёт status 200, Content-Type и Content-Disposition. Его код не устанавливает собственный контракт Range/206/416, ETag, Last-Modified, HEAD/OPTIONS и дополнительных cache headers. Отсутствие явной controller-ветки не доказывает отсутствие поведения Spring MVC/Security. Фактический wire-контракт этих framework-сценариев остаётся OPEN-SRV-029; утверждение «Range отсутствует» не включено в гарантии.

Публичные библиотечные поверхности: `/swagger-ui/**`, `/swagger-resources/*`, `/v3/api-docs/**`. Они не являются дополнительными прикладными handlers из списка 36. `/error` отдельно не разрешён security configuration; вторичный error dispatch может влиять на необработанные ошибки (OPEN-SRV-030). Actuator/прикладного health API нет.
