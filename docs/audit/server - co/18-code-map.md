# 18. Карта кода и контроль полноты

Срез: 2026-09-10, HEAD84f7a547aabceac0849baef520605dac7111e797. [CONFIRMED] Карта охватывает все100 production Java files,8 test Java files,14 SQL migrations, runtime/test YAML и связанные ресурсы/документы. Имена ниже — реальные files/classes; вложенные test fixtures не отдельные production компоненты.

## Как ориентироваться

- HTTP method/path и DTO validation:05 и client-handoff/01/04.
- ORM/entity vs SQL:03–04.
- JWT/request ownership:06.
- FS pipeline/аварии:07; end-to-end:08; состояния:09.
- Error handling/retry:10.
- Config/jobs/testing/logging:11–14.
- Features/риски/открытые требования:15–17.

## Production packages

### ru.tbcarus.photocloudserver

[CONFIRMED] Bootstrap зависит от Spring Boot; загружает конфигурацию и beans остальных packages.

Ключевые классы (полный состав package):

- [PhotoCloudServerApplication](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/PhotoCloudServerApplication.java) — Точка входа SpringApplication; component scan корневого package.

### ru.tbcarus.photocloudserver.config

[CONFIRMED] Spring bean wiring. SecurityConfig→filter beans; EncoderConfig→password consumers; MailConfig→EmailService.

Ключевые классы (полный состав package):

- [EncoderConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/EncoderConfig.java) — Bean BCryptPasswordEncoder без explicit strength; используется registration/login/reset.
- [MailConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/MailConfig.java) — Custom JavaMailSender и SpringTemplateEngine/HTML resolver, SMTP/debug literals.
- [SecurityConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java) — Stateless filter chain, permitAll paths, default authenticated, CSRF off, custom entry/denied.

### ru.tbcarus.photocloudserver.config.filter

[CONFIRMED] HTTP cross-cutting pipeline; Jwt filter→UserService/JwtService; JSON handlers→ErrorResponse/ObjectMapper.

Ключевые классы (полный состав package):

- [HttpLoggingFilter](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/HttpLoggingFilter.java) — HTTP wrappers, INFO request/response bodies/query; binary masking,1000 text chars; кэш ответа.
- [JsonAccessDeniedHandler](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/JsonAccessDeniedHandler.java) — 403 FORBIDDEN JSON ErrorResponse.
- [JsonAuthenticationEntryPoint](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/JsonAuthenticationEntryPoint.java) — 401 UNAUTHORIZED JSON ErrorResponse.
- [JwtAuthenticationFilter](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/JwtAuthenticationFilter.java) — Bearer ACCESS JWT parse; UserService DB principal; JWT exceptions→entrypoint.

### ru.tbcarus.photocloudserver.controller

[CONFIRMED] REST boundary. Вызывает services, FolderMapper; Profile вручную DTO. ApiPaths сам не controller.

Ключевые классы (полный состав package):

- [ApiPaths](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/ApiPaths.java) — Единый API_V1=/api/v1 для controller constants.
- [AuthController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java) — 5 маршрутов login/refresh/logout/all/others → UserService.
- [FileController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java) — 11 маршрутов upload aliases, list/card/download, rename/move/copy/delete, checksum list/exists → FileItemService/ChecksumSyncService.
- [FolderController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java) — 6 маршрутов ROOT/children/create/rename/move/delete → FolderService и FolderMapper.
- [PasswordController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java) — 5 маршрутов recovery: request/confirm и3 заглушки → UserService.
- [RegisterController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RegisterController.java) — 3 маршрута register/confirm/resend → UserService/EmailRequestService.
- [RootController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RootController.java) — 2 connection pings: public/authenticated; без прикладных записей.
- [UserController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/UserController.java) — 4 маршрута profile/settings; GET вручную строит UserDto, остальные3 —501.

### ru.tbcarus.photocloudserver.exception

[CONFIRMED] Domain/runtime markers и MVC advice; используется services, handlers формируют exception.dto.

Ключевые классы (полный состав package):

- [BadRegistrationRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/BadRegistrationRequest.java) — RuntimeException с ErrorType для email code validation.
- [DuplicateEmailException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/DuplicateEmailException.java) — RuntimeException marker →409 CONFLICT.
- [EntityAlreadyExistException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/EntityAlreadyExistException.java) — Неиспользуемый RuntimeException entityName/message, handler нет.
- [EntityNotFoundException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/EntityNotFoundException.java) — RuntimeException entityName/message →400 BAD_REQUEST; например recovery unknown user.
- [ErrorType](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/ErrorType.java) — Historical email error titles; NOT_FOUND используется, limiter branch TOO_MUCH_REPEAT_REQUESTS unused.
- [FileConflictException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FileConflictException.java) — RuntimeException message →409 CONFLICT.
- [FileItemNotFoundException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FileItemNotFoundException.java) — RuntimeException logical file ID →404 FILE_ITEM_NOT_FOUND.
- [FileNotFoundException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FileNotFoundException.java) — Неиспользуемый custom RuntimeException FileName/message; handler404 NOT_FOUND есть.
- [FileSizeLimitExceededException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FileSizeLimitExceededException.java) — RuntimeException streaming byte limit →413 FILE_TOO_LARGE.
- [FolderConflictException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FolderConflictException.java) — RuntimeException folder name/reserved →409 CONFLICT.
- [FolderNotFoundException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FolderNotFoundException.java) — RuntimeException own-folder lookup →404 NOT_FOUND.
- [FolderOperationException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/FolderOperationException.java) — RuntimeException system/cycle/nonempty/name rule →400 BAD_REQUEST.
- [GlobalExceptionHandler](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java) — Конкретные exception→HTTP ErrorResponse; generic/IO catch-all нет.
- [InvalidCredentialsException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/InvalidCredentialsException.java) — Marker RuntimeException login →401 INVALID_CREDENTIALS.
- [InvalidRefreshTokenException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/InvalidRefreshTokenException.java) — Marker RuntimeException refresh →401 INVALID_REFRESH_TOKEN.
- [RefreshTokenNotFoundException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/RefreshTokenNotFoundException.java) — Marker RuntimeException logout lookup →404 REFRESH_TOKEN_NOT_FOUND.
- [RefreshTokenOwnershipException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/RefreshTokenOwnershipException.java) — Marker RuntimeException logout ownership →403.
- [TickerRequestException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/TickerRequestException.java) — Неиспользуемый legacy RuntimeException с message; handler400 есть.
- [TokenRevokedException](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/TokenRevokedException.java) — RuntimeException token/message; output403 REFRESH_TOKEN_REVOKED; token field не выводится handler.

### ru.tbcarus.photocloudserver.exception.dto

[CONFIRMED] Единая форма части ошибок, сериализуется Jackson.

Ключевые классы (полный состав package):

- [ErrorCode](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/dto/ErrorCode.java) — 16 error enum names; INTERNAL_ERROR не используется catch-all.
- [ErrorResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/dto/ErrorResponse.java) — JSON error id/code/message/fieldErrors.

### ru.tbcarus.photocloudserver.model

[CONFIRMED] Persistence entities, enums и EmailContext. Используется repositories/services/mappers; User реализует Security API.

Ключевые классы (полный состав package):

- [EmailContext](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/EmailContext.java) — Lombok DTO rendering/send контекста, не entity; часть полей не используется sender.
- [EmailRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/EmailRequest.java) — Entity email_requests; UUID code/type/used/user/createdAt, computed expiry3days.
- [EmailRequestType](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/EmailRequestType.java) — ACTIVATE/PASSWORD_RESET + email subjects.
- [FileItem](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/FileItem.java) — Logical file entity, owner/folder/object/checksum/name/times, optional metadata cascade.
- [FileMetadata](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/FileMetadata.java) — Optional1:1 metadata entity, dimensions/EXIF/GPS/duration placeholder.
- [FileType](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/FileType.java) — IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/OTHER MIME classification.
- [Folder](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/Folder.java) — Logical folder entity, self-parent, owner/type/name/timestamps.
- [FolderType](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/FolderType.java) — ROOT/CAMERA/FILES/USER enum.
- [RefreshToken](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/RefreshToken.java) — Entity refresh_token, raw token/userName/expires/revoked/revokedAt без User FK.
- [Role](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/Role.java) — USER/ADMIN enum GrantedAuthority, ROLE_ prefix.
- [StoredObject](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/StoredObject.java) — Physical file entity: owner/path/name/extension/hash/size/MIME/type.
- [TokenType](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/TokenType.java) — JWT claim key token_type и ACCESS/REFRESH значения.
- [User](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/User.java) — Entity users + UserDetails; roles EAGER; username=email.

### ru.tbcarus.photocloudserver.model.dto

[CONFIRMED] 21 client-visible DTO/request class, Validation/Jackson/Lombok; используется controllers и mappers.

Ключевые классы (полный состав package):

- [ChecksumExistsRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/ChecksumExistsRequest.java) — DTO: folderId required + list64hex validation. Wire поля перечислены в client-handoff/04.
- [ChecksumExistsResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/ChecksumExistsResponse.java) — DTO: existing/missing arrays. Wire поля перечислены в client-handoff/04.
- [CopyFileRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/CopyFileRequest.java) — DTO: Optional targetFolderId/originalName (max255). Wire поля перечислены в client-handoff/04.
- [CreateFolderRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/CreateFolderRequest.java) — DTO: Optional parentId, required name max255. Wire поля перечислены в client-handoff/04.
- [FileChecksumDto](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/FileChecksumDto.java) — DTO: id/originalFilename/checksum. Wire поля перечислены в client-handoff/04.
- [FileItemDto](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/FileItemDto.java) — DTO: Logical wire file card, physical metadata без storage path. Wire поля перечислены в client-handoff/04.
- [FileMetadataDto](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/FileMetadataDto.java) — DTO: Nullable dimensions/EXIF/GPS; fNumber JsonProperty. Wire поля перечислены в client-handoff/04.
- [FolderDto](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/FolderDto.java) — DTO: id/parentId/name/folderType/createdAt/updatedAt. Wire поля перечислены в client-handoff/04.
- [LoginRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/LoginRequest.java) — DTO: Validated email/password4..20. Wire поля перечислены в client-handoff/04.
- [LoginResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/LoginResponse.java) — DTO: accessToken + refreshToken. Wire поля перечислены в client-handoff/04.
- [LogoutRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/LogoutRequest.java) — DTO: Nonblank refreshToken для logout/others. Wire поля перечислены в client-handoff/04.
- [MoveFileRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/MoveFileRequest.java) — DTO: Required targetFolderId. Wire поля перечислены в client-handoff/04.
- [MoveFolderRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/MoveFolderRequest.java) — DTO: Required targetParentId. Wire поля перечислены в client-handoff/04.
- [PageResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/PageResponse.java) — DTO: items/page/size/totals/next/previous. Wire поля перечислены в client-handoff/04.
- [PasswordResetConfirmRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/PasswordResetConfirmRequest.java) — DTO: JSON password4..20 + code required. Wire поля перечислены в client-handoff/04.
- [RefreshRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/RefreshRequest.java) — DTO: Nonblank refreshToken. Wire поля перечислены в client-handoff/04.
- [RefreshResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/RefreshResponse.java) — DTO: accessToken only. Wire поля перечислены в client-handoff/04.
- [RegisterRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/RegisterRequest.java) — DTO: Validated email/password4..20, displayName excluded. Wire поля перечислены в client-handoff/04.
- [RenameFileRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/RenameFileRequest.java) — DTO: Required originalName max255. Wire поля перечислены в client-handoff/04.
- [RenameFolderRequest](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/RenameFolderRequest.java) — DTO: Required name max255. Wire поля перечислены в client-handoff/04.
- [UserDto](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/UserDto.java) — DTO: Profile ID/email/displayName/flags/roles/timestamps. Wire поля перечислены в client-handoff/04.

### ru.tbcarus.photocloudserver.model.dto.mapper

[CONFIRMED] MapStruct annotation-generated Spring implementations; используются services/controllers.

Ключевые классы (полный состав package):

- [FileItemMapper](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/FileItemMapper.java) — MapStruct FileItem→FileItemDto; physical fields из StoredObject; metadata fNumber mapping.
- [FolderMapper](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/FolderMapper.java) — MapStruct Folder→FolderDto, parent.id→parentId.
- [UserRegisterMapper](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/UserRegisterMapper.java) — MapStruct RegisterRequest→User; reverse mapping unused.

### ru.tbcarus.photocloudserver.repository

[CONFIRMED] Spring Data JPA; requests к PostgreSQL. FileItemRepository зависит от DTO projection.

Ключевые классы (полный состав package):

- [EmailRequestRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/EmailRequestRepository.java) — JpaRepository<EmailRequest,Integer> (ID mismatch); code lookup/user-date-type queries; getByCode/getByUserIdAndCode unused.
- [FileItemRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java) — Long ID; EntityGraphs; owner/folder pages; duplicates; checksum projection/exists; names; references by object.
- [FileMetadataRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/FileMetadataRepository.java) — Long ID bean; production injection/calls не обнаружены, persistence через cascade.
- [FolderRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/FolderRepository.java) — Long ID; root/children/owner/name queries; case-insensitive JPQL; parent existence.
- [RefreshTokenRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/RefreshTokenRepository.java) — JpaRepository<RefreshToken,Integer> (ID mismatch); lookup raw token/userName/revoked; optional historical expiry method unused.
- [StoredObjectRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/StoredObjectRepository.java) — Long ID; inherited CRUD used; own user+checksum lookup unused в active pipeline.
- [UserRepository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository/UserRepository.java) — JpaRepository<User,Long>; email exists и JPQL email=lower(input), ID lookup.

### ru.tbcarus.photocloudserver.service

[CONFIRMED] Основная бизнес-оркестрация, транзакции, безопасность ownership и filesystem coordination.

Ключевые классы (полный состав package):

- [EmailRequestService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java) — Generate/validate/consume ACTIVATE/PASSWORD_RESET; transactional flags/password; unused limiter/alternate confirm, empty delete.
- [EmailService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailService.java) — Render email HTML and send SMTP; URL строит из HTTP request + controller constants.
- [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java) — Upload pipeline/compensation; logical file CRUD/copy/download; repositories + FolderService + storage + metadata.
- [FolderService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java) — Lazy system folders, ownership, USER structure/name/cycle/empty checks; Folder/FileItem repos.
- [JwtService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java) — JWT generate/parse/validate; persist refresh, revoke one/all/others; repository.
- [UserService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/UserService.java) — UserDetailsService; orchestrates register/login/refresh/logout/reset; repos, JWT, BCrypt, email.

### ru.tbcarus.photocloudserver.service.metadata

[CONFIRMED] FileItemService→FileMetadataExtractor interface→Drew implementation→metadata-extractor library.

Ключевые классы (полный состав package):

- [DrewFileMetadataExtractor](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/metadata/DrewFileMetadataExtractor.java) — Единственная реализация: image EXIF/GPS/JPEG/PNG; failures→empty result.
- [ExtractedFileMetadata](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/metadata/ExtractedFileMetadata.java) — Immutable Lombok Value/Builder для промежуточных nullable metadata и hasMetadataFields().
- [FileMetadataExtractor](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/metadata/FileMetadataExtractor.java) — Интерфейс извлечения metadata из Path/MIME.

### ru.tbcarus.photocloudserver.service.storage

[CONFIRMED] Path/name/config/MIME helpers для FileItemService, не отдельный remote object-store adapter.

Ключевые классы (полный состав package):

- [FileContentDetector](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage/FileContentDetector.java) — Tika.detect(Path) для реального MIME.
- [FilenameSanitizer](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage/FilenameSanitizer.java) — Заменяет опасные символы, extension/name length, physical suffix.
- [StorageKeyGenerator](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage/StorageKeyGenerator.java) — User/hash-prefix sharding и physical filename с UUID.
- [StoragePathResolver](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage/StoragePathResolver.java) — root + relative path + filename, normalize/startsWith containment.
- [StorageProperties](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage/StorageProperties.java) — ConfigurationProperties storage, root/temp/100MiB/name prefix80.

### ru.tbcarus.photocloudserver.service.sync

[CONFIRMED] FileController→ChecksumSyncService→FolderService/FileItemRepository.

Ключевые классы (полный состав package):

- [ChecksumExistsProperties](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumExistsProperties.java) — ConfigurationProperties sync.checksum-exists, maxBatchSize500.
- [ChecksumSyncService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncService.java) — Validate raw batch limit; own folder; lowercase/dedup/partition hashes по FileItemRepository.

### ru.tbcarus.photocloudserver.util

[CONFIRMED] FileUtils вызывается upload; ConfigUtil — reset; DateUtil — Thymeleaf footer.

Ключевые классы (полный состав package):

- [ConfigUtil](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/util/ConfigUtil.java) — Дублирующиеся email constants; DEFAULT_EXPIRED_DAYS используется reset workflow; прочие helpers callers не найдены.
- [DateUtil](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/util/DateUtil.java) — Дата/format helpers. getLocalDateTimeNow и DTFORMATTER_RU используются Thymeleaf footer; остальные helpers в production не вызываются.
- [FileUtils](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/util/FileUtils.java) — Stream buffer8192 → SHA256 + size + output, max-size exception.

## Ресурсы / build / documentation modules

| Package/module | Назначение | Связи и источник |
| --- | --- | --- |
| [CONFIRMED] build.gradle/settings.gradle | Single Gradle project, dependencies, Java17, test config | [build.gradle](C:/projects/photo-cloud-server/build.gradle), [settings](C:/projects/photo-cloud-server/settings.gradle) |
| [CONFIRMED] gradle/wrapper + gradlew(.bat) | Gradle8.13 bootstrap | [wrapper properties](C:/projects/photo-cloud-server/gradle/wrapper/gradle-wrapper.properties) |
| [CONFIRMED] src/main/resources/application.yml | Runtime DB/mail/JWT/storage/sync/log properties | [YAML](C:/projects/photo-cloud-server/src/main/resources/application.yml); полный registry11 |
| [CONFIRMED] src/test/resources/application-test.yml | Integration profile defaults | [test YAML](C:/projects/photo-cloud-server/src/test/resources/application-test.yml) |
| [CONFIRMED] db/changelog | master includeAll +14 files/16 changesets | [master](C:/projects/photo-cloud-server/src/main/resources/db/changelog/db.changelog-master.yml); history/schema04 |
| [CONFIRMED] db/audit | Read-only pre14 duplicate diagnosis, не migration | [SQL](C:/projects/photo-cloud-server/db/audit/check-file-item-folder-checksum-duplicates-before-migration-14.sql) |
| [CONFIRMED] templates/email | Confirmation/reset HTML | [confirmation](C:/projects/photo-cloud-server/src/main/resources/templates/email/confirmationTemplate.html), [reset](C:/projects/photo-cloud-server/src/main/resources/templates/email/passwordResetTemplate.html) → EmailService |
| [CONFIRMED] templates/fragments | Footer реально используется обоими email templates; headTag include закомментирован | [footer](C:/projects/photo-cloud-server/src/main/resources/templates/fragments/footer.html) → DateUtil; [headTag](C:/projects/photo-cloud-server/src/main/resources/templates/fragments/headTag.html) |
| [CONFIRMED] static/css/style.css | Styles для templates, legacy UI классы | [CSS](C:/projects/photo-cloud-server/src/main/resources/static/css/style.css) |
| [CONFIRMED] docs/api-*-contract.md |4 существующих контракта | Сверены, расхождения client-handoff/07 |
| [CONFIRMED] docs/application-overview.md | Ранее написанный обзор | [overview](C:/projects/photo-cloud-server/docs/application-overview.md) |
| [CONFIRMED] README/README-description/TODO | Назначение, планы, исторические требования | [README](C:/projects/photo-cloud-server/README.md), [description](C:/projects/photo-cloud-server/README-description.md), [TODO](C:/projects/photo-cloud-server/TODO.txt) |
| [PARTIAL] docs/http-tests |26 manual requests; curl file без команд | [HTTP](C:/projects/photo-cloud-server/docs/http-tests/api-smoke-tests.http);13 и differences07 |
| [CONFIRMED] .gitignore | Исключает build/.gradle/storage/.env* и IDE files | [ignore](C:/projects/photo-cloud-server/.gitignore) |

[CONFIRMED] .env.local/.env.docker находятся в resources и недоступны для чтения в среде аудита. Не использовались как источник значений. .idea files не являются runtime deployment contract. Локальные build/storage найдены, их generated outputs/пользовательские bytes не приняты за актуальный серверный source of truth. HELP.md — справочный scaffold со ссылками, новой server logic не содержит.

## Test packages

- [PhotoCloudServerApplicationTests.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/PhotoCloudServerApplicationTests.java) — 1 @Test; полный список сценариев в13.
- [AbstractIntegrationTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/AbstractIntegrationTest.java) — Общий SpringBoot/MockMvc/Testcontainers setup, clean DB+storage, HTTP logging helpers.
- [AuthErrorHandlingIntegrationTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/AuthErrorHandlingIntegrationTest.java) — 30 @Test; полный список сценариев в13.
- [FileFlowIntegrationTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java) — 39 @Test; полный список сценариев в13.
- [FolderApiIntegrationTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/FolderApiIntegrationTest.java) — 13 @Test; полный список сценариев в13.
- [ProfileIntegrationTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/ProfileIntegrationTest.java) — 1 @Test; полный список сценариев в13.
- [FileItemServiceTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/service/FileItemServiceTest.java) — 2 @Test; полный список сценариев в13.
- [ChecksumSyncServiceTest.java](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncServiceTest.java) — 2 @Test; полный список сценариев в13.

## Финальная сверка охвата

| Проверка | Результат | Где отражено |
| --- | --- | --- |
| [CONFIRMED] Controllers |7: Auth5, File11, Folder6, Password5, Register3, Root2, User4 |05, client-handoff/01; сумма36 |
| [CONFIRMED] Endpoint реализации |29 routes с business/read logic +7 STUB501 |05,15, capabilities |
| [CONFIRMED] Entities |7: User,RefreshToken,EmailRequest,Folder,StoredObject,FileItem,FileMetadata |03 |
| [CONFIRMED] (source: SQL) Прикладные таблицы |8 текущих + historical media_file |04; user_roles без отдельной entity |
| [CONFIRMED] (source: SQL) Migrations |14 файлов,16 changesets; last tbcarus:14-file-item-user-folder-checksum |04; applied live state неизвестен |
| [CONFIRMED] DTO/mappers |21 DTO +3 mapper; ErrorResponse отдельно |client-handoff/04,03 |
| [CONFIRMED] Repositories |7 |02,04, эта карта |
| [CONFIRMED] Java coverage of reading |100 production files +8 test files |Эта карта и13 |
| [CONFIRMED] Tests declared |88 @Test |13; не запускались |
| [CONFIRMED] Auth |Register/login/refresh/logout/reset, public paths, ownership, gaps |06; handoff02 |
| [CONFIRMED] Storage |Paths, tmp/final, commit boundaries, copy/delete, missing/orphan |07–10; handoff05 |
| [CONFIRMED] Config |Tracked YAML + Java values; local env недоступны |11 |
| [CONFIRMED] Background |Прикладные scheduler/queues/async jobs не найдены |12 |
| [CONFIRMED] Errors |Все17 custom exceptions, framework gaps |10; handoff03 |
| [CONFIRMED] Documentation |4 API docs + overview + READMEs/TODO + HTTP suite |05 и handoff07 |
| [CONFIRMED] Handoff |8 автономных файлов;36 routes;21 DTO + errors |client-handoff |
| [CONFIRMED] Delivery |19 main +8 handoff =27 Markdown reports |Только docs/audit/server |

## Результат заключительной машинной сверки

[CONFIRMED] После создания отчётов выполнена read-only сверка деклараций controllers с разделами 05-api.md и client-handoff/01-api-contract-effective.md: в каждом документе ровно36 уникальных method/path, пропусков и лишних маршрутов нет. Все7 entities присутствуют в03; все14 SQL-файлов — в04; все21 DTO — в client-handoff/04; все100 production Java files имеют ссылки в этой карте.

[CONFIRMED] Проверены существование локальных Markdown-ссылок, равенство числа колонок таблиц, парность fenced blocks, отсутствие символов повреждённой кодировки и JWT literals в отчётах. Ошибок этих проверок не найдено. Mermaid-диаграммы проверены как текстовые блоки; отдельный Mermaid renderer не запускался.

[CONFIRMED] SHA-256 всех150 существовавших tracked файлов совпали со снимком до записи отчётов. Git status показал только27 новых файлов внутри docs/audit/server и49 удалений старых отчётов, уже присутствовавших в начале работы; новых изменений вне каталога аудита нет. Это проверка неизменности файлов, а не запуск тестов сервера.

## Границы подтверждения

Аудит статический. Наличие тестового сценария не означает его успешного выполнения. Не проверялись доступность deployed server, actual database schema/rows, SMTP delivery, реальное использование Android, нагрузка, filesystem corruption и секретные environment values. Предположения о сбоях/гонках помечены RISK/INFERRED, отсутствующие продуктовые требования —17 и handoff08. Новые API/DDL/code/tests не добавлялись; прежние документы проекта не редактировались.
