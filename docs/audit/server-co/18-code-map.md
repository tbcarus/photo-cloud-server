# Карта исходников и контроль полноты

Срез: c2e9593a30270fddd2fb80d3f2d6ff0c533d2d37. [CONFIRMED] 100 production Java source files, 8 test Java source files, 14 SQL migrations, 1 master changelog. Базовый Java package: ru.tbcarus.photocloudserver.

## Корень / сборка

PhotoCloudServerApplication — единственная точка SpringBootApplication. build.gradle — зависимости, Java17, JUnit Platform и вывод stdout тестов. settings.gradle — один модуль. gradlew/gradlew.bat и gradle-wrapper.properties — Gradle8.13. .gitignore исключает build/.gradle/storage/.env*/env.properties/IDE state. Tracked Docker/compose/CI files не найдены.

## controller

AuthController — login, refresh, logout/all/others (5 mappings), вызывает UserService.
RegisterController — register/confirm/resend (3), вызывает UserService/EmailRequestService.
PasswordController — reset request/confirm/resend/page GET+POST (5), вызывает UserService.
UserController — profile GET/PATCH/settings GET/PATCH (4), DTO создаётся из principal.
RootController — test public/auth (2).
FileController — upload два URL, list/card/download/rename/move/copy/delete/checksum-list/exists (11), вызывает FileItemService/ChecksumSyncService.
FolderController — root/children/create/rename/move/delete (6), вызывает FolderService/FolderMapper.
ApiPaths — /api/v1.

[CONFIRMED] Итого5+3+5+4+2+11+6=36. На каждом mapping есть @Operation summary; 7 зарезервированных handlers возвращают501. Inventory details и endpoint test coverage —05-api.md.

## service

UserService — Users + регистрация/auth/password orchestration, UserDetailsService; потребляет repositories/JWT/email/mapper/encoder.
JwtService — token claims/signature/parse/DB refresh state/revocation; вызывают UserService и JWT filter.
EmailRequestService — generation/code validation/confirm/reset; transactional changes user/codes.
EmailService — HTML context/request-origin links + synchronous SMTP.
FileItemService — file operations/storage pipeline/compensation/TransactionTemplate; использует FolderService.
FolderService — lazy system folders, user tree restrictions; использует folder/file repositories.

## service.storage / service.metadata / service.sync

StorageProperties — root/temp/size/filename prefix length.
StoragePathResolver — root containment и absolute path.
StorageKeyGenerator — user/checksum shards/UUID names.
FilenameSanitizer — logical name, extension, physical component.
FileContentDetector — Tika MIME.
FileMetadataExtractor — interface.
DrewFileMetadataExtractor — image EXIF/GPS/JPEG-PNG size, exception fallback.
ExtractedFileMetadata — transient result и hasMetadataFields.
ChecksumExistsProperties — maxBatchSize500.
ChecksumSyncService — folder ownership, batch cap/normalize/dedup, query and ordered groups.

## repository

UserRepository — exists email, normalized email lookup, ID.
RefreshTokenRepository — token/email lookups, JpaRepository ID Integer при model Long.
EmailRequestRepository — code/window/count, ID Integer при model Long.
FolderRepository — root/system/children/sibling name predicates.
FileItemRepository — graphs/list/card/duplicate/checksum projections/exists name+folder.
StoredObjectRepository — inherited persistence; checksum lookup объявлен без production caller.
FileMetadataRepository — объявлен, не используется отдельной injection; metadata cascade через FileItem.

[CONFIRMED] findWithRelationsById — используется тестовыми helpers; production lookup для клиента findByIdAndUserId. Generic JpaRepository предоставляет больше методов, чем фактически вызвано приложением; это не дополнительные API возможности.

## model и model.dto

Entities: User, RefreshToken, EmailRequest, Folder, FileItem, StoredObject, FileMetadata. Enums: Role, TokenType, EmailRequestType, FolderType, FileType. EmailContext — transient mail context.

DTO: ChecksumExistsRequest/Response, CopyFileRequest, CreateFolderRequest, FileChecksumDto, FileItemDto, FileMetadataDto, FolderDto, LoginRequest/Response, LogoutRequest, MoveFileRequest, MoveFolderRequest, PageResponse, PasswordResetConfirmRequest, RefreshRequest/Response, RegisterRequest, RenameFileRequest, RenameFolderRequest, UserDto —21 исходный DTO.

Mappers: UserRegisterMapper (register→User; reverse unused), FolderMapper (parentId), FileItemMapper (physical attrs from SO + metadata). Реализации генерирует MapStruct; generated build files не authoritative handwritten code.

## config / filter

SecurityConfig — whitelist/authenticated/STATELESS/CSRF off.
EncoderConfig — BCryptPasswordEncoder bean.
MailConfig — explicit JavaMailSender, SMTP/TLS/debug flags, SpringTemplateEngine resolver.
JwtAuthenticationFilter — Bearer access verification/load User/auth context.
JsonAuthenticationEntryPoint —401 ErrorResponse.
JsonAccessDeniedHandler —403 ErrorResponse.
HttpLoggingFilter — earliest servlet filter, cached bodies/INFO logs.

## exception

GlobalExceptionHandler —21 @ExceptionHandler declarations для выбранных ошибок (точный перечень в10-errors-retries.md).
17 RuntimeException classes: BadRegistrationRequest, DuplicateEmailException, EntityAlreadyExistException, EntityNotFoundException, FileConflictException, FileItemNotFoundException, FileNotFoundException, FileSizeLimitExceededException, FolderConflictException, FolderNotFoundException, FolderOperationException, InvalidCredentialsException, InvalidRefreshTokenException, RefreshTokenNotFoundException, RefreshTokenOwnershipException, TickerRequestException, TokenRevokedException.
ErrorType — старые code-validation reason titles.
exception.dto.ErrorCode/ErrorResponse — wire envelope, INTERNAL_ERROR не имеет найденного producer.

[UNUSED] EntityAlreadyExistException, TickerRequestException, старый FileNotFoundException не имеют production throw sites; у последних двух handler остаётся. Это не значит, что активный FileItemNotFoundException не используется.

## util / resources

FileUtils — streaming SHA256+byte limit.
DateUtil — footer использует getLocalDateTimeNow/DTFORMATTER_RU; spending-period helpers и остальные форматы в production callers не найдены.
ConfigUtil — DEFAULT_EXPIRED_DAYS используется reset code window; прочие constants/helpers в production flow не найдены.
application.yml — единственная production конфигурация; application-test.yml — тестовые overrides.
templates/email/confirmationTemplate.html, passwordResetTemplate.html — HTML письма; link показан в span, не anchor; browser link auto-detection зависит от mail client.
templates/fragments/footer.html — используется email templates; headTag fragment включение в них закомментировано.
static/css/style.css — статический CSS, ссылки из templates; отдельного whitelist /css/** нет.
db/changelog/db.changelog-master.yml + table01–14 — история DDL; ручной SELECT db/audit — вне includeAll.

## Документы и их статус

api-user-contract.md, api-file-contract.md, api-folder-contract.md, api-checksum-sync-contract.md — сверены с controller/DTO/service.
application-overview.md — близок к текущей файловой модели.
README.md / README-description.md / TODO.txt — смешивают работающие функции и пожелания; не override код.
docs/http-tests/README.md, api-smoke-tests.http, api-curl-examples.md — ручные инструкции, частично устаревшие.
Не найден файл с точным именем api-contract.md.

## Контроль полноты аудита

| Объект | Инвентаризация | Где описан |
| --- | --- | --- |
| Контроллеры | 7/7 | 02,05,18; handoff01 |
| Явные HTTP mappings | 36/36;29 work+7 stub | 05; handoff01 |
| Entities | 7/7 | 03 |
| Прикладные tables | 8/8 (с user_roles) | 04 |
| SQL migrations | 14/14,16 changesets | 04 |
| Auth/security/error handlers | Все найденные filter/config/service | 06,09,10,14 |
| Disk upload/copy/delete | Все ветви и cleanup | 07,08,09 |
| DTO | 21/21 + ErrorResponse | handoff04, handoff03 |
| Configuration | YAML + properties classes + @Value + constants + build/test | 11 |
| Background mechanisms | Поиск declarations/callers, отсутствующие jobs явно отмечены | 12 |
| Tests | 88 declarations, assertions/fixtures reviewed; runtime не запускался | 13 |
| Existing docs comparison | 4 API contracts + overview/README/TODO/http | 05; handoff07 |
| Android handoff | 8 документов, ссылки внутри пакета | client-handoff/ |
| Production changes | Не выполнялись | git diff проверяется при завершении |

Следующие пофайловые указатели автоматически собраны по текущему дереву для проверяемости coverage; они не заменяют смысловые разделы выше.

