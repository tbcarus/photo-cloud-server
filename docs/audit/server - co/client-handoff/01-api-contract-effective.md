# 01. Эффективный API-контракт для Android

Срез: 2026-09-10, server HEAD 84f7a547aabceac0849baef520605dac7111e797. [CONFIRMED] Контракт восстановлен по controllers/services/DTO, не по работающему серверу. Установленный сервер может иметь другое окружение или revision.

## Как читать

36 явных операций под /api/v1. JSON по умолчанию для DTO; upload — multipart, download — bytes, register confirm — текст, delete — пустой204. Public не требует JWT; access означает `Authorization: Bearer <accessToken>` (с пробелом и этим регистром). Public-запрос с expired/malformed Bearer может получить401 ещё до обработки body, поэтому refresh/login не нуждаются в таком заголовке.

Общий app error: `{id:string UUID,code:string,message:string,fieldErrors:object|null}`. Для IOException/framework ошибок форма не гарантирована; 501 имеет только message. Все защищённые операции могут вернуть401 UNAUTHORIZED. Обычные invalid DTO→400 VALIDATION_ERROR, malformed/missing JSON→400 BAD_REQUEST. Полная таблица — файл03 этого пакета.

Типы ID: JSON number/int64; timestamps: LocalDateTime в ISO local datetime без Z/offset. Client wire schema всех DTO — файл04 этого пакета. Длительности access20min/refresh7days не возвращаются expiresIn. Нет общего Idempotency-Key протокола.

## Операции

### GET /api/v1/test

[CONFIRMED]

- Auth: public.
- Request: Нет параметров/тела.
- Response: 200 JSON {message: "All good! Permit all connection"}.
- Errors: Общие security/framework ошибки.
- Idempotency/ограничения повторов: Да, чтение.

### GET /api/v1/test/auth

[CONFIRMED]

- Auth: access.
- Request: Нет параметров/тела.
- Response: 200 JSON {message: "All good! Authenticated connection. Hello <email>"}.
- Errors: Общие security/framework ошибки.
- Idempotency/ограничения повторов: Да.

### POST /api/v1/auth/register

[CONFIRMED]

- Auth: public.
- Request: JSON RegisterRequest: email string @NotBlank @Email; password string @NotBlank @Size(4..20). displayName не объявлен. Email длиной >128 не отвергается DTO-лимитом.
- Response: 201 JSON {message: "Email was sent"}.
- Errors: 400 VALIDATION_ERROR/BAD_REQUEST; 409 CONFLICT email; DB constraint 500 при гонке/длине; SMTP/runtime не имеют единого ErrorResponse.
- Idempotency/ограничения повторов: Нет: повтор существующего email →409; успех не подтверждает доставку письма.

### GET /api/v1/auth/register/confirm

[CONFIRMED]

- Auth: public.
- Request: Query code string обязателен; @NotBlank нет.
- Response: 200 текст User <email> was verified.
- Errors: 400 BAD_REGISTRATION_REQUEST при missing DB code/used/expired/wrong type; missing query →400 BAD_REQUEST.
- Idempotency/ограничения повторов: Нет одинакового ответа: использованный код →400, user остаётся enabled.

### POST /api/v1/auth/register/resend

[CONFIRMED]

- Auth: public.
- Request: Нет объявленного тела/параметров.
- Response: 501 JSON {message: "Registration confirmation resend is not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Постоянная заглушка.

### POST /api/v1/auth/login

[CONFIRMED]

- Auth: public.
- Request: JSON LoginRequest: email @NotBlank @Email; password @NotBlank, length 4..20.
- Response: 200 LoginResponse {accessToken:string, refreshToken:string}. Нет expiresIn/tokenType.
- Errors: 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_CREDENTIALS для unknown email, bad password, disabled/banned.
- Idempotency/ограничения повторов: Нет: каждый успех создаёт новый refresh (UUID jti).

### POST /api/v1/auth/refresh-token

[CONFIRMED]

- Auth: public.
- Request: JSON RefreshRequest {refreshToken:string @NotBlank}. Передавать refresh в body, не как access Bearer.
- Response: 200 RefreshResponse {accessToken:string}. Refresh остаётся прежним.
- Errors: 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_REFRESH_TOKEN unknown/malformed/expired/wrong type/subject; 403 REFRESH_TOKEN_REVOKED; invalid Authorization →401 UNAUTHORIZED до controller.
- Idempotency/ограничения повторов: Не потребляет refresh; повтор допустим, access заново генерируется.

### POST /api/v1/auth/logout

[CONFIRMED]

- Auth: access.
- Request: JSON LogoutRequest {refreshToken:string @NotBlank}; token должен принадлежать principal.
- Response: 200 JSON {message: "Logged out successfully"}.
- Errors: 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.
- Idempotency/ограничения повторов: Повтор собственного существующего token →200, revokedAt обновится.

### POST /api/v1/auth/logout-all

[CONFIRMED]

- Auth: access.
- Request: Нет тела/параметров.
- Response: 200 JSON {message: "All logged out successfully"}.
- Errors: Общие security/framework ошибки.
- Idempotency/ограничения повторов: Повтор →200; новые login между запросами меняют множество.

### POST /api/v1/auth/logout-others

[CONFIRMED]

- Auth: access.
- Request: JSON LogoutRequest {refreshToken:string @NotBlank}; существующий token текущего user. Его expiry/revoked не проверяются.
- Response: 200 JSON {message: "All other logged out successfully"}.
- Errors: 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.
- Idempotency/ограничения повторов: Повтор не создаёт новых записей; не привязан к device.

### POST /api/v1/auth/password/reset/request

[CONFIRMED]

- Auth: public.
- Request: Обязательный query email string @NotBlank; @Email здесь отсутствует. JSON email не является объявленным контрактом.
- Response: 200 JSON {message: "Email was sent"}.
- Errors: 400 VALIDATION_ERROR blank; 400 BAD_REQUEST missing query/unknown user; SMTP/runtime нестандартизованы.
- Idempotency/ограничения повторов: Нет: повтор создаёт новый код и письмо, rate-limit не подключён.

### POST /api/v1/auth/password/reset/confirm

[CONFIRMED]

- Auth: public.
- Request: JSON PasswordResetConfirmRequest: password @NotBlank size4..20; code @NotBlank. Legacy query-only не принимается.
- Response: 200 JSON {message: "Password was reset"}.
- Errors: 400 VALIDATION_ERROR/BAD_REQUEST; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code.
- Idempotency/ограничения повторов: Повтор consumed code →400.

### POST /api/v1/auth/password/reset/resend

[CONFIRMED]

- Auth: public.
- Request: Нет объявленного тела/параметров.
- Response: 501 JSON {message: "Password reset resend is not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### GET /api/v1/auth/password/reset/page

[CONFIRMED]

- Auth: public.
- Request: Query code string обязателен, иначе 400; code не проверяется по БД.
- Response: 501 JSON {message: "Password reset page is not implemented yet"}. HTML не возвращается.
- Errors: 400 BAD_REQUEST missing query; 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### POST /api/v1/auth/password/reset/page

[CONFIRMED]

- Auth: public.
- Request: Нет объявленного тела/параметров; form не обрабатывается.
- Response: 501 JSON {message: "Password reset page submit is not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### GET /api/v1/profile

[CONFIRMED]

- Auth: access.
- Request: Нет параметров.
- Response: 200 UserDto: id,email,displayName,enabled,banned,roles,createdAt,lastUpdate,lastLoginAt.
- Errors: Общие security/framework ошибки.
- Idempotency/ограничения повторов: Да.

### PATCH /api/v1/profile

[CONFIRMED]

- Auth: access.
- Request: Нет request DTO; присланные поля не обрабатываются.
- Response: 501 JSON {message: "Profile update is not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### GET /api/v1/profile/settings

[CONFIRMED]

- Auth: access.
- Request: Нет параметров.
- Response: 501 JSON {message: "Profile settings are not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### PATCH /api/v1/profile/settings

[CONFIRMED]

- Auth: access.
- Request: Нет DTO; присланные настройки не применяются.
- Response: 501 JSON {message: "Profile settings update is not implemented yet"}.
- Errors: 501 не ErrorResponse.
- Idempotency/ограничения повторов: Заглушка.

### GET /api/v1/folders/root

[CONFIRMED]

- Auth: access.
- Request: Нет параметров.
- Response: 200 FolderDto; parentId=null, name=root, folderType=ROOT.
- Errors: DB race может привести к DB/runtime ошибке; нет обещания универсального 409.
- Idempotency/ограничения повторов: Повтор возвращает тот же ROOT; GET имеет write side effect.

### GET /api/v1/folders/{id}/children

[CONFIRMED]

- Auth: access.
- Request: Path id: Long; обязательный; без pagination/recursive/query filters.
- Response: 200 JSON array FolderDto, прямые потомки, sort lower(name),id.
- Errors: 404 NOT_FOUND missing/foreign parent.
- Idempotency/ограничения повторов: Да.

### POST /api/v1/folders

[CONFIRMED]

- Auth: access.
- Request: JSON CreateFolderRequest: parentId optional Long (null→ROOT); name @NotBlank @Size(max255); service trim. Parent ROOT/USER.
- Response: 200 FolderDto новой USER-папки.
- Errors: 400 VALIDATION_ERROR/BAD_REQUEST system leaf/blank/length; 404 NOT_FOUND parent; 409 CONFLICT occupied/reserved name; DB race может дать500.
- Idempotency/ограничения повторов: Нет: повтор имени →409.

### PATCH /api/v1/folders/{id}

[CONFIRMED]

- Auth: access.
- Request: Path id Long. JSON RenameFolderRequest {name:string @NotBlank @Size(max255)}; trim. Только USER.
- Response: 200 FolderDto.
- Errors: 400 BAD_REQUEST system folder / VALIDATION_ERROR; 404 NOT_FOUND; 409 CONFLICT occupied/reserved.
- Idempotency/ограничения повторов: Повтор имени допустим, updatedAt может измениться.

### POST /api/v1/folders/{id}/move

[CONFIRMED]

- Auth: access.
- Request: Path id Long; JSON MoveFolderRequest {targetParentId:Long @NotNull}; target ROOT/USER.
- Response: 200 FolderDto.
- Errors: 400 BAD_REQUEST system/self/descendant/system target; 404 NOT_FOUND; 409 CONFLICT names/reserved; validation400.
- Idempotency/ограничения повторов: Повтор того же parent допустим; конкурентное изменение дерева не сериализовано.

### DELETE /api/v1/folders/{id}

[CONFIRMED]

- Auth: access.
- Request: Path id Long; только USER без children и files.
- Response: 204 без тела.
- Errors: 400 BAD_REQUEST system/nonempty; 404 NOT_FOUND; DB race500 возможен.
- Idempotency/ограничения повторов: Эффект удаления повторим; следующий запрос404.

### POST /api/v1/files

[CONFIRMED]

- Auth: access.
- Request: multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка.
- Response: 200 FileItemDto и при создании, и при duplicate; нет Location/upload-status.
- Errors: 400 BAD_REQUEST empty file/invalid params; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler.
- Idempotency/ограничения повторов: В пределах user+folder+bytes SHA-256; rename нового request игнорируется при duplicate. Не восстанавливает отсутствующий physical file.

### POST /api/v1/files/upload

[CONFIRMED]

- Auth: access.
- Request: multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка.
- Response: 200 FileItemDto; тот же pipeline, что POST /files.
- Errors: 400 BAD_REQUEST empty file/invalid params; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler.
- Idempotency/ограничения повторов: Те же ограничения, что POST /files.

### GET /api/v1/files

[CONFIRMED]

- Auth: access.
- Request: Query page int default0 >=0, size int default10 >0 (PageRequest); верхний предел size не установлен; optional folderId Long, только прямые файлы. type/from/to/sort не объявлены.
- Response: 200 PageResponse<FileItemDto> {items,page,size,totalElements,totalPages,hasNext,hasPrevious}. Sort capturedAt DESC,uploadedAt DESC,id DESC.
- Errors: 400 BAD_REQUEST negative page/size0; type-conversion400 форма не задана custom handler;404 NOT_FOUND folder.
- Idempotency/ограничения повторов: Чтение; между страницами нет snapshot/cursor guarantee.

### GET /api/v1/files/{id}

[CONFIRMED]

- Auth: access.
- Request: Path id Long.
- Response: 200 FileItemDto; physical path/filename/StoredObject ID не раскрываются.
- Errors: 404 FILE_ITEM_NOT_FOUND missing/foreign file.
- Idempotency/ограничения повторов: Да.

### GET /api/v1/files/{id}/download

[CONFIRMED]

- Auth: access.
- Request: Path id Long. Пользовательское Range/ETag поведение не реализовано явно и не закреплено тестами.
- Response: 200 Resource/raw bytes; Content-Type = stored detectedMimeType; Content-Disposition: attachment; filename="<originalName>".
- Errors: 404 FILE_ITEM_NOT_FOUND missing/foreign/unreadable or absent physical; IOException без общего custom500; invalid DB MIME/path может дать400.
- Idempotency/ограничения повторов: Да для неизменного объекта; bytes проверяются только exists/isReadable, checksum не пересчитывается.

### PATCH /api/v1/files/{id}

[CONFIRMED]

- Auth: access.
- Request: Path id Long; JSON RenameFileRequest {originalName:string @NotBlank @Size(max255)}. Trim + sanitize. Request originalName отличается от response originalFilename.
- Response: 200 FileItemDto.
- Errors: 400 validation/BAD_REQUEST;404 FILE_ITEM_NOT_FOUND;409 CONFLICT name outside CAMERA; race DB errors500.
- Idempotency/ограничения повторов: Повтор того же нормализованного имени допустим.

### POST /api/v1/files/{id}/move

[CONFIRMED]

- Auth: access.
- Request: Path id Long; JSON MoveFileRequest {targetFolderId:Long @NotNull}. Любая своя папка, включая системную.
- Response: 200 FileItemDto.
- Errors: 400 validation;404 FILE_ITEM_NOT_FOUND/NOT_FOUND folder;409 CONFLICT name outside CAMERA;500 DATABASE_CONSTRAINT_VIOLATION при checksum conflict если name-check пропущен/прошёл.
- Idempotency/ограничения повторов: Повтор target допустим если constraints соблюдены; 500 checksum повтор не исправит.

### POST /api/v1/files/{id}/copy

[CONFIRMED]

- Auth: access.
- Request: Path id Long; JSON CopyFileRequest обязателен, {} допустим. targetFolderId optional(null→source folder); originalName optional @Size(max255), null/blank→source name; иначе trim+sanitize.
- Response: 200 новый FileItemDto.
- Errors: 400 body/validation;404 file/folder;409 CONFLICT name/checksum;500 constraint race; missing source bytes→IOException без специального404 handler.
- Idempotency/ограничения повторов: Не возвращает существующий объект при повторе: target checksum→409. Copy в исходную папку всегда конфликтует.

### DELETE /api/v1/files/{id}

[CONFIRMED]

- Auth: access.
- Request: Path id Long.
- Response: 204 без тела.
- Errors: 404 FILE_ITEM_NOT_FOUND; DB errors500; IOException удаления bytes только логируется и не отменяет204.
- Idempotency/ограничения повторов: Повтор →404; не повторяет неудавшееся физическое удаление, если DB row уже нет.

### GET /api/v1/files/checksums

[CONFIRMED]

- Auth: access.
- Request: Нет query/pagination/filter параметров.
- Response: 200 массив FileChecksumDto {id,originalFilename,checksum}; все FileItem user; folderId отсутствует, порядок не задан.
- Errors: Общие security/framework ошибки.
- Idempotency/ограничения повторов: Да; одинаковый checksum может повторяться для разных папок.

### POST /api/v1/files/checksums/exists

[CONFIRMED]

- Auth: access.
- Request: JSON ChecksumExistsRequest: folderId Long @NotNull; checksums @NotEmpty List; каждый @NotBlank и 64 hex regex. Max raw list size500 до dedup; без trim. Lowercase Locale.ROOT и dedup первого появления.
- Response: 200 ChecksumExistsResponse {existing:string[],missing:string[]}; каждый hash один раз в своей partition, порядок первого появления; нет file IDs.
- Errors: 400 VALIDATION_ERROR null/blank/invalid;400 BAD_REQUEST batch>max;404 NOT_FOUND folder;401.
- Idempotency/ограничения повторов: Да, read-only; результат может устареть до upload.

## Ключевые ограничения

[CONFIRMED] Нет поиска по имени/типу/дате, delta feed, upload-init/complete, resumable chunks, квоты, share API, альбомов, thumbnails, device registration, серверных sync-статусов, изменения bytes существующего файла. Filename в request rename/copy называется originalName, в ответе originalFilename. folderId для pre-check обязателен, для upload optional. Список checksum не содержит folderId и не подходит как точная замена folder-scoped pre-check.

[CONFIRMED] ROOT создаётся GET /folders/root; CAMERA/FILES создаются лениво при upload без folderId. GET root/children не гарантирует, что Camera уже есть; специального create/get CAMERA endpoint нет. Правила первого pre-check Camera требуют отдельного решения клиента (вопросы в08).

Источники для проверки контракта: [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [DTO](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [SecurityConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java). Для использования этого handoff чтение исходников не требуется.
