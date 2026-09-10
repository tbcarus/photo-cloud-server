# End-to-end процессы

[CONFIRMED] Источники: controllers; UserService/JwtService/EmailRequestService/EmailService; FileItemService/FolderService/ChecksumSyncService; repositories. Ошибки — GlobalExceptionHandler и JSON security handlers. Схемы показывают синхронный request path.

## Login

Вход: JSON email/password. UserService.login → UserRepository.findByEmailIgnoreCase → BCrypt matches → enabled/banned → lastLoginAt save → JwtService.generateAccessToken → generateRefreshToken + RefreshTokenRepository.save → 200 два JWT. БД: lastLoginAt/lastUpdate + refresh row; filesystem нет. Unknown/password/banned/disabled → 401 INVALID_CREDENTIALS. Если token persistence не удалась, lastLoginAt уже мог измениться; общей транзакции нет.

~~~mermaid
sequenceDiagram
    participant C as Client
    participant A as AuthController
    participant U as UserService
    participant D as PostgreSQL
    participant J as JwtService
    C->>A: POST login JSON
    A->>U: login(request)
    U->>D: lookup email
    U->>U: BCrypt + enabled + banned
    U->>D: save lastLoginAt
    U->>J: generateAccessToken
    J-->>U: access JWT
    U->>J: generateRefreshToken
    J->>D: insert refresh JWT
    J-->>U: refresh JWT
    U-->>A: LoginResponse
    A-->>C: 200 accessToken, refreshToken
~~~

## Refresh / logout

Refresh input — JSON refreshToken без access header. UserService → JwtService.getRefreshToken → DB → user → refreshAccessToken (revoked, signature/type/exp/sub) → 200 только accessToken. БД не меняется; bytes нет. Invalid →401, revoked →403. Итог: прежняя refresh row и новый access.

Logout input — access header + refresh body. Filter loads User, JwtService checks row owner, revoke saves true/now, response 200. Logout-all без body; logout-others принимает исключаемый token; bulk не записывает revokedAt. Access остаются действовать. При повторном login старые refresh сохраняются.

~~~mermaid
sequenceDiagram
    participant C as Client
    participant A as Auth API
    participant J as JwtService
    participant D as PostgreSQL
    C->>A: POST refresh-token {refreshToken}
    A->>D: find refresh row and user
    A->>J: refreshAccessToken
    J->>J: revoked / signature / type / exp / subject
    J-->>C: 200 new accessToken
    C->>A: POST logout + access + refresh
    A->>J: revokeOwnedToken
    J->>D: verify owner, revoked=true, revokedAt=now
    A-->>C: 200
    C->>A: POST refresh-token with revoked refresh
    A-->>C: 403 REFRESH_TOKEN_REVOKED
~~~

## Регистрация и password recovery

Register: valid DTO → save disabled USER + role → generate ACTIVATE code → render HTML из HTTP request origin → SMTP → 201 message. MessagingException ловится частично; MailException/template runtime не ловятся этим catch. Пользователь/код уже сохраняются отдельно. Confirm GET code → transaction code validate → user.enabled=true + code.used=true → 200 string.

Reset request: email → lookup (unknown →400) → новый PASSWORD_RESET code без cooldown → письмо со ссылкой на GET reset/page, сейчас 501. Рабочий путь: POST reset/confirm JSON password/code → transactional password hash update + used reset codes → 200. Refresh/access не отзываются. Повторный code →400, доставки/прочтения письма сервер не отслеживает.

## Проверка существования и duplicate detection

Вход: access + JSON folderId + checksums. MVC validates list/items, ChecksumSyncService ограничивает исходный size 500, FolderService проверяет owner, нормализует в LinkedHashSet lowercase, FileItemRepository.findExistingChecksumsInFolder возвращает distinct lower(FileItem.checksum), формируются existing/missing в порядке ввода. Выход 200; никаких INSERT/physical operations.

[CONFIRMED] Duplicate pre-check и реальный upload — разные запросы без lock/reservation. Между ними файл может быть создан/удалён/перемещён. Existing означает logical presence, не физическую целостность.

## Upload и повторная загрузка

Вход: access, multipart file, optional folderId. FileController (оба upload URL) → FileItemService. Последовательность: empty → temp stream SHA/size → MIME/type → image metadata/time fallback → target folder → sanitize name → existing tuple → name check → UUID final move → transaction save SO/FileItem/metadata → DTO 200.

Default IMAGE/VIDEO → lazy Camera; остальные → lazy Files. На explicit folderId ограничение file type не проверяется; загрузить документ в CAMERA по её ID код допускает. Empty →400; owner folder →404; name →409; size →413; DB constraints →500 кроме recovered duplicate. Исключения чистят temp/final best effort. Полная crash matrix в 07-file-storage.md.

~~~mermaid
sequenceDiagram
    participant C as Client
    participant A as FileController
    participant S as FileItemService
    participant FS as Filesystem
    participant D as PostgreSQL
    C->>A: POST files or files/upload
    A->>S: uploadFile(file,user,folderId)
    S->>FS: create temp, stream bytes + SHA-256/size
    S->>FS: detect MIME and extract metadata
    S->>D: target folder and tuple lookup
    alt same user + folder + checksum exists
        S->>FS: delete temp
        S-->>C: 200 existing FileItemDto
    else new tuple
        S->>D: name check
        S->>FS: temp to final (atomic if supported)
        S->>D: transaction insert SO + FileItem + metadata
        alt commit success
            S-->>C: 200 new FileItemDto
        else DB exception
            S->>FS: best-effort delete final
            S->>D: lookup winner if constraint exception
            S-->>C: existing DTO or error
        end
    end
~~~

[CONFIRMED] Повтор byte-identical файла в ту же папку сохраняет старый ID/name/uploadedAt/metadata. Иное имя не выполняет rename. Другой folder — новый physical file. Одинаковое имя с другим checksum вне Camera →409. Checksum supplied upload field нет: client pre-check hash не сравнивается с отдельным request checksum.

## Список / карточка / download

List: page/size/defaults → PageRequest fixed sort → FileItemRepository EntityGraph → DTO/PageResponse. FolderId проверяется на owner, список только direct files; без folderId все пользовательские файлы. Count/pagination в БД, диск не читается, deletedAt не фильтруется.

Card: id+user lookup → FileItemMapper → 200. Download: тот же lookup → StoragePathResolver → UrlResource.exists/isReadable → headers/bytes. Missing bytes →404, checksum не пересчитывается. DTO содержит логическое имя, но physical metadata из SO. Собственной Range/resume state machine нет.

## Rename / move / copy / delete

| Процесс | Вход и вызовы | БД | Filesystem / выход |
| --- | --- | --- | --- |
| Rename | id,originalName → ownership → normalize/name check | save original_name | Без disk, 200 DTO |
| Move | id,targetFolderId → ownership обоих → name check | save folder_id | Без disk, 200 DTO; checksum DB conflict может 500 |
| Copy | id,optional target/name → ownership → name/checksum check | transaction new SO/FileItem/clone metadata | Files.copy до DB, 200 new DTO; cleanup после DB error |
| Owner delete | id → owned FileItem → compare SO owner | transaction delete all refs, flush, delete SO | Files.deleteIfExists после commit, 204 даже при IOException |
| Nonowner SO delete | id → own logical reference | delete только FileItem (+metadata) | Physical untouched, 204 |

~~~mermaid
sequenceDiagram
    participant C as Client
    participant S as FileItemService
    participant D as PostgreSQL
    participant F as Filesystem
    C->>S: DELETE files/id + access
    S->>D: find owned FileItem and SO
    alt logical owner equals physical owner
        S->>D: begin; delete all references + SO; commit
        S->>F: deleteIfExists
        opt IOException
            S->>S: log ERROR
        end
    else owns only logical reference
        S->>D: delete own FileItem
    end
    S-->>C: 204
~~~

## Папки

ROOT GET лениво создаёт root и возвращает DTO; children GET читает только один уровень. Create USER: parentId optional/root fallback → trim/not blank/max255 → parent не Camera/Files → reserved-name и sibling-name checks → save. Rename/move/delete запрещены для system folders. Move проверяет self/descendant цепочку; delete требует отсутствие children/files. Транзакции FolderService охватывают DB, физические каталоги не создаются/не меняются. Ошибки 400/404/409, конкурентные DB failures могут выйти за локальный catch.

