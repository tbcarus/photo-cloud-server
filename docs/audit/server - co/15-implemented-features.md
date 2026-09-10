# 15. Master feature matrix

Статусы относятся к server As-Is, не к планам. IMPLEMENTED означает найденную работающую ветвь кода; тестовые результаты не подтверждены запуском. PARTIAL — часть сценария; STUB — endpoint/метод без реализации; UNUSED — не найден active production caller; NOT FOUND — проверенная в рамках задачи capability отсутствует. Доказательность в Notes.

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Регистрация email/password | IMPLEMENTED | RegisterController, UserService | [CONFIRMED]201, BCrypt, disabled USER |
| Подтверждение email | IMPLEMENTED | EmailRequestService.confirmRegistration | [CONFIRMED]3days, одноразовая проверка used;200 текст |
| Повторная отправка подтверждения | STUB | RegisterController.resendVerifyEmail | [CONFIRMED]501 |
| Ограничение email-запросов | UNUSED | EmailRequestService.checkAndGenerateCode | [UNUSED] Не вызывается register/forgotPassword |
| Login | IMPLEMENTED | UserService.login | [CONFIRMED] Password + enabled/banned; lastLoginAt |
| Access/refresh JWT | IMPLEMENTED | JwtService | [CONFIRMED]20min/7days, token_type |
| Refresh | IMPLEMENTED | UserService.refreshToken, JwtService | [CONFIRMED] Только новый access, без rotation |
| Logout / all / others | IMPLEMENTED | AuthController, JwtService | [CONFIRMED] Отзыв refresh; access valid до exp |
| HTTP session | NOT FOUND | SecurityConfig.STATELESS | [CONFIRMED] Сохраняется token state, не HttpSession |
| Device-aware sessions | NOT FOUND | model/DTO/controllers | [CONFIRMED] Нет deviceId; несколько refresh rows возможны |
| Password reset JSON confirm | IMPLEMENTED | PasswordController, EmailRequestService | [CONFIRMED] Hash update + used codes; токены остаются |
| End-to-end reset из письма | PARTIAL | EmailService, PasswordController | [PARTIAL] Ссылка ведёт на page501 |
| Resend password reset | STUB | PasswordController.resendResetPassword | [CONFIRMED]501 |
| HTML reset page / submit | STUB | PasswordController GET/POST page | [CONFIRMED]501 JSON |
| Просмотр профиля | IMPLEMENTED | UserController.getProfile | [CONFIRMED] UserDto без hash |
| Изменение профиля | STUB | UserController.updateProfile | [CONFIRMED]501 |
| Read/update settings | STUB | UserController settings | [CONFIRMED]501, data model нет |
| User deletion | NOT FOUND | Controllers/services | [CONFIRMED] TODO есть, HTTP/service flow нет |
| Role data | IMPLEMENTED | User.roles, Role | [CONFIRMED] USER/ADMIN сохраняются |
| Role-based business permissions | PARTIAL | SecurityConfig, services | [PARTIAL] Authenticated + ownership; роль ADMIN не даёт отдельные операции |
| Logical ROOT/Camera/Files | IMPLEMENTED | FolderService | [CONFIRMED] Lazy folders |
| USER folder create/children | IMPLEMENTED | FolderController/Service | [CONFIRMED] Direct children, nested USER |
| Folder rename/move | IMPLEMENTED | FolderService | [CONFIRMED] System restrictions/cycle prechecks |
| Empty folder delete | IMPLEMENTED | FolderService.deleteFolder | [CONFIRMED] Hard delete empty USER |
| Recursive folder delete | NOT FOUND | FolderService TODO | [CONFIRMED] Отказ для nonempty |
| Multipart upload aliases | IMPLEMENTED | FileController POST base/upload | [CONFIRMED] Один file/pipeline, optional folder |
| Streaming size/hash | IMPLEMENTED | FileUtils, FileItemService | [CONFIRMED]8KiB buffer, service100MiB |
| MIME detection/classification | IMPLEMENTED | FileContentDetector, FileType | [CONFIRMED] Tika path; categories6 |
| Image EXIF/GPS | PARTIAL | DrewFileMetadataExtractor | [PARTIAL] Best effort; dimensions JPEG/PNG, nullable fields |
| Video/audio storage | IMPLEMENTED | FileType, upload/download | [CONFIRMED] Bytes принимаются; нет transcode |
| Video duration/metadata | PARTIAL | FileMetadata.durationSec, extractor | [PARTIAL] Поле есть; extractor video не обрабатывает |
| File listing/pagination | IMPLEMENTED | FileController, FileItemRepository | [CONFIRMED] user/folder, fixed ordering |
| Metadata card | IMPLEMENTED | GET files/{id}, mapper | [CONFIRMED] Physical details скрыты |
| Download | IMPLEMENTED | Resource response | [CONFIRMED] Stored MIME + logical filename; память logging wrapper — риск |
| Rename file | IMPLEMENTED | FileItemService.renameFileForCurrentUser | [CONFIRMED] Logical name only |
| Move file | IMPLEMENTED | FileItemService.moveFileForCurrentUser | [CONFIRMED] Logical folder only; checksum conflict500 |
| Physical file copy | IMPLEMENTED | copyFileForCurrentUser | [CONFIRMED] New object/bytes; same-folder checksum409 |
| Hard delete file | IMPLEMENTED | deleteFileForCurrentUser | [CONFIRMED] Owner removes all references; FS best effort |
| Trash/restore | PARTIAL | FileItem.deletedAt | [UNUSED] Поле не используется current flows |
| User-folder checksum dedup | IMPLEMENTED | FileItemService, migration14 | [CONFIRMED] Upload existing200, copy duplicate409 |
| Physical cross-folder dedup | NOT FOUND | upload/copy | [CONFIRMED] Намеренно новые StoredObject/bytes |
| Full checksum listing | IMPLEMENTED | GET files/checksums | [CONFIRMED] Legacy documented, actual client usage unknown |
| Batch checksum pre-check | IMPLEMENTED | ChecksumSyncService | [CONFIRMED] folder-scoped max500 |
| Full sync / delta / upload sessions | NOT FOUND | model/controllers | [CONFIRMED] Только pre-check; не протокол reconcile |
| Resume/chunks/upload complete | NOT FOUND | FileController | [CONFIRMED] Нет upload session endpoints |
| Sharing/link-existing | PARTIAL | StoredObject relation, delete branch | [PARTIAL] Model/test support references, API создания share/link отсутствует |
| Checksum object lookup | UNUSED | StoredObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc | [UNUSED] Production caller не найден |
| File metadata repository bean | UNUSED | FileMetadataRepository | [UNUSED] Metadata сохраняется cascade |
| EmailRequest.delete | STUB | EmailRequestService.delete | [CONFIRMED] Пустое тело, caller не найден |
| Alternate confirmEmail | UNUSED | EmailRequestService.confirmEmail | [UNUSED] HTTP использует confirmRegistration |
| Album/tags/search/thumbnail/version/overwrite | NOT FOUND | README/docs TODO; current controllers | [CONFIRMED] Отсутствуют реализованные endpoints/entities; это проверка заявленного scope |
| Quota/storage capacity error | NOT FOUND | README-description local-state idea; code | [CONFIRMED] Только per-file limit, общей квоты нет |
| Scheduled cleanup/jobs | NOT FOUND | src/build inventory | [CONFIRMED] Нет scheduler/async/queue |
| Controlled error DTO | PARTIAL | GlobalExceptionHandler, security handlers | [PARTIAL] Не covers IO/generic errors/501 |
| HTTP/email logs | IMPLEMENTED | HttpLoggingFilter, EmailService | [CONFIRMED] Есть, без sensitive masking в production |
| Metrics/health/tracing | NOT FOUND | build/controllers/config | [CONFIRMED] Только test ping, не полноценный health |
| Automated tests | IMPLEMENTED | src/test | [CONFIRMED]88 declarations; не запускались аудитом |
| Manual smoke suite | PARTIAL | docs/http-tests | [PARTIAL] Не все endpoint, curl файл без команд |
| Liquibase schema | IMPLEMENTED | migrations01–14 | [CONFIRMED]16 changesets; applied live version неизвестна |

Источники: [controller](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [service](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [model](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model), [tests](C:/projects/photo-cloud-server/src/test/java), [SQL](C:/projects/photo-cloud-server/src/main/resources/db/changelog/table).
