# Server capabilities

Идентификаторы закреплены за описанными capabilities и не зависят от номера endpoint. NOT PRESENT означает отсутствие прикладной реализации в рассматриваемой версии; внешняя инфраструктура не исследована. Группа строк с несколькими отсутствующими механизмами описывает одну границу capability, а не заявляет новые требования.

| ID | Feature | Status | Main mechanism | Notes |
| --- | --- | --- | --- | --- |
| SRV-AUTH-001 | Регистрация | IMPLEMENTED | UserService + email code | 201; disabled user до confirm |
| SRV-AUTH-002 | Подтверждение регистрации | IMPLEMENTED | EmailRequestService | 3 дня, used/type check;200 text |
| SRV-AUTH-003 | Login и BCrypt | IMPLEMENTED | UserService/JwtService | enabled/banned проверяются на login |
| SRV-AUTH-004 | Access JWT | IMPLEMENTED | JWT token_type=ACCESS | 20 минут; без persisted revoke |
| SRV-AUTH-005 | Refresh JWT и refresh endpoint | IMPLEMENTED | refresh_token + JWT | 7 дней; новый access без rotation |
| SRV-AUTH-006 | Logout одного refresh | IMPLEMENTED | Ownership + revoked/revokedAt | Access остаётся до exp |
| SRV-AUTH-007 | Logout всех refresh | IMPLEMENTED | Revoke list по userName | revokedAt не обновляется |
| SRV-AUTH-008 | Logout остальных refresh | IMPLEMENTED | Revoke list кроме переданного | Переданный token проверяется на наличие/ownership |
| SRV-AUTH-009 | Reset request и JSON confirm | IMPLEMENTED | EmailRequestService | Новый hash; JWT не отзываются |
| SRV-AUTH-010 | Восстановление из email-ссылки | PARTIAL | SMTP link → reset/page | Page501; JSON confirm существует |
| SRV-AUTH-011 | Registration resend | STUB | RegisterController | 501 |
| SRV-AUTH-012 | Reset resend | STUB | PasswordController | 501 |
| SRV-AUTH-013 | Reset page GET/POST | STUB | PasswordController | Оба501; GET требует code |
| SRV-AUTH-014 | Email rate limiter helper | UNUSED | checkAndGenerateCode | Не вызывается active flows |
| SRV-AUTH-015 | Роли и блокировка | PARTIAL | User.roles/enabled/banned | Нет admin management; флаги только login |
| SRV-AUTH-016 | ADMIN и method-security annotations | UNUSED | Role.ADMIN/EnableMethodSecurity | Отдельных role rules нет |
| SRV-AUTH-017 | Отзыв access, rotation refresh, revoke при reset | NOT PRESENT | — | Отсутствуют в текущих flows |
| SRV-AUTH-018 | MFA/OAuth/rate limit | NOT PRESENT | — | Нет прикладного механизма |
| SRV-AUTH-019 | Email send | IMPLEMENTED | Thymeleaf/JavaMail | Синхронно; success не удостоверяет delivery |
| SRV-AUTH-020 | Email delete helper | STUB | EmailRequestService.delete | Пустое тело, не cleanup |
| SRV-PROFILE-001 | Чтение профиля | IMPLEMENTED | UserController principal DTO | Без password |
| SRV-PROFILE-002 | Изменение профиля | STUB | PATCH profile | 501 |
| SRV-PROFILE-003 | Чтение/изменение settings | STUB | GET/PATCH settings | 501, settings entity нет |
| SRV-PROFILE-004 | Смена email, удаление account, avatar | NOT PRESENT | — | Не предоставлены API |
| SRV-FOLDER-001 | Lazy ROOT | IMPLEMENTED | getOrCreateRoot + unique ROOT | GET может писать; race caveat |
| SRV-FOLDER-002 | Lazy CAMERA/FILES | IMPLEMENTED | Default upload | GET root их не создаёт |
| SRV-FOLDER-003 | Direct children | IMPLEMENTED | FolderRepository | lower(name), id; без pagination |
| SRV-FOLDER-004 | Create USER | IMPLEMENTED | FolderService | Parent ROOT/USER;200 |
| SRV-FOLDER-005 | Rename USER | IMPLEMENTED | FolderService | System/reserved/name checks |
| SRV-FOLDER-006 | Move USER | IMPLEMENTED | Parent-chain check | Конкурентная ацикличность не гарантирована |
| SRV-FOLDER-007 | Delete empty USER | IMPLEMENTED | Empty children/files checks | 204; затем404 |
| SRV-FOLDER-008 | Системные ограничения и sibling uniqueness | IMPLEMENTED | Service + SQL | Без изменения system folders |
| SRV-FOLDER-009 | Recursive delete/full tree/breadcrumb API | NOT PRESENT | — | Только direct children и parentId |
| SRV-FILE-001 | Upload single file двумя aliases | IMPLEMENTED | FileItemService | 200 новый или existing DTO |
| SRV-FILE-002 | Выбор upload target | IMPLEMENTED | Explicit folderId / MIME default | Любая своя папка при explicit target |
| SRV-FILE-003 | Same-folder duplicate | IMPLEMENTED | user+folder+checksum | Старые имя/metadata/dates; без bytes repair |
| SRV-FILE-004 | List и folder filter | IMPLEMENTED | PageResponse + fixed sort | page0/size10, без upper size |
| SRV-FILE-005 | Metadata GET | IMPLEMENTED | FileItemMapper | Без FS availability check |
| SRV-FILE-006 | Download | IMPLEMENTED | UrlResource + response cache | 200 ordinary response; framework details OPEN |
| SRV-FILE-007 | Rename | IMPLEMENTED | FileItem.originalName | Physical filename прежний |
| SRV-FILE-008 | Move | IMPLEMENTED | FileItem.folder | Checksum conflict может стать500 |
| SRV-FILE-009 | Copy | IMPLEMENTED | Files.copy + новая пара | Same-folder checksum409; physical independence |
| SRV-FILE-010 | Hard delete | IMPLEMENTED | Owner/non-owner branch | Owner удаляет все references; FS best effort |
| SRV-FILE-011 | Физическое завершение удаления | PARTIAL | deleteIfExists после commit | Ошибка IO только log;204 |
| SRV-FILE-012 | Soft delete marker | UNUSED | deletedAt | API не присваивает и не фильтрует |
| SRV-FILE-013 | Trash/restore/version/overwrite/auto-rename | NOT PRESENT | — | Не выводятся из deletedAt/TODO |
| SRV-FILE-014 | Batch/chunk/resumable upload | NOT PRESENT | — | Один multipart file; без upload session |
| SRV-FILE-015 | Type/date filters и configurable sort | NOT PRESENT | — | Только folderId/page/size |
| SRV-FILE-016 | Ограничение типов / MIME allowlist / антивирусная проверка | NOT PRESENT | — | Любой detected MIME принимается; тип влияет на классификацию/default folder |
| SRV-MEDIA-001 | MIME/SHA-256/size | IMPLEMENTED | Tika и FileUtils | По принятым bytes |
| SRV-MEDIA-002 | Image metadata | PARTIAL | Drew extractor | JPEG/PNG dimensions, EXIF/GPS best effort |
| SRV-MEDIA-003 | capturedAt | IMPLEMENTED | EXIF с fallback uploadedAt | LocalDateTime, без offset |
| SRV-MEDIA-004 | Video metadata/duration | NOT PRESENT | — | Bytes video сохраняются; durationSec не заполняется |
| SRV-MEDIA-005 | Thumbnails/transcode/album/tags/search | NOT PRESENT | — | Не являются текущими моделями |
| SRV-SYNC-001 | Checksum full list | IMPLEMENTED | GET files/checksums | Все FileItem user; без folderId и pagination |
| SRV-SYNC-002 | Folder checksum pre-check | IMPLEMENTED | ChecksumSyncService | 500 raw; lowercase/dedup/order |
| SRV-SYNC-003 | Полный sync protocol | PARTIAL | Pre-check и отдельные CRUD | Нет reconcile/delta/tombstones |
| SRV-SYNC-004 | Device/sync/upload sessions/push | NOT PRESENT | — | Нет persisted state/queue |
| SRV-SYNC-005 | Link-existing/sharing API | NOT PRESENT | — | Допустимые references в модели не означают endpoint |
| SRV-STORAGE-001 | Local binary storage | IMPLEMENTED | Path/key/sanitizer helpers | UserId + checksum shards + UUID |
| SRV-STORAGE-002 | Lexical path protection | IMPLEMENTED | Normalize + startsWith | Не realpath/symlink check |
| SRV-STORAGE-003 | Upload size limits | IMPLEMENTED | Service100MiB/servlet110MB | Не total quota |
| SRV-STORAGE-004 | DB/FS compensation | PARTIAL | Move/copy + TransactionTemplate catches | Нет общей atomic transaction |
| SRV-STORAGE-005 | Cross-folder physical dedup | NOT PRESENT | — | Независимые объекты |
| SRV-STORAGE-006 | Orphan cleanup/quota/repair/encryption layer/S3 | NOT PRESENT | — | Внешние средства deployment неизвестны |
| SRV-STORAGE-007 | Object lookup по checksum | UNUSED | StoredObjectRepository helper | Active upload ищет FileItem в folder |
| SRV-API-001 | Прикладные endpoints | IMPLEMENTED | 7 controllers/36 mappings | 29 с логикой +7 STUB |
| SRV-API-002 | Unified controlled errors | PARTIAL | Advice + security handlers | Не все framework/IO/501 |
| SRV-API-003 | Swagger/OpenAPI | IMPLEMENTED | springdoc | Public docs paths |
| SRV-API-004 | Idempotency-Key/retry queue | NOT PRESENT | — | Только семантика конкретных операций |
| SRV-OPS-001 | HTTP/service logs | IMPLEMENTED | Logback + filters/services | Без sensitive masking |
| SRV-OPS-002 | Health/metrics/tracing/audit registry | NOT PRESENT | — | Test ping не readiness |
| SRV-OPS-003 | Background jobs | NOT PRESENT | — | Работа синхронная |
| SRV-OPS-004 | Liquibase schema | IMPLEMENTED | 14 SQL/16 changesets | Applied production state неизвестен |
| SRV-OPS-005 | Automated tests | IMPLEMENTED | JUnit/MockMvc/Testcontainers | В этой работе не запускались |
| SRV-OPS-006 | Manual smoke coverage | PARTIAL | HTTP examples | Не все endpoints; curl file без команд |
| SRV-OPS-007 | CI/coverage/deployment/backup artifacts | NOT PRESENT | — | По обоим аудитам в поставляемом проекте |
