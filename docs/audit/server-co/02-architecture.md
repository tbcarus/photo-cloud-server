# Архитектура As-Is

[CONFIRMED] Один Gradle module (settings.gradle), один SpringBootApplication, общий package ru.tbcarus.photocloudserver. Нет отдельных deployable storage/auth/sync сервисов. [INFERRED] Стиль — слоистый монолит с REST-входом и repository persistence; это классификация структуры, не заявленный архитектурный стандарт.

## Слои и зависимости

Пути ниже относительно src/main/java/ru/tbcarus/photocloudserver/.

| Слой/package | Назначение и классы | Вход | Выход | Прямые зависимости |
| --- | --- | --- | --- | --- |
| controller | Auth, Register, Password, User, Root, File, FolderController; ApiPaths | HTTP, DTO, User principal, multipart | ResponseEntity, DTO, Resource | services, model, DTO, mapper; FileController также Spring Data pagination |
| service | UserService | registration/login/password/logout | tokens/user/void | UserRepository, mapper, JwtService, PasswordEncoder, EmailRequestService, EmailService |
| service | JwtService | User/JWT/refresh row | JWT/claims/отзыв | RefreshTokenRepository, JJWT, signing config |
| service | EmailRequestService | User/type/code/password | code/user mutation | EmailRequestRepository, UserRepository, encoder |
| service | EmailService | EmailRequest | SMTP side effect | JavaMailSender, SpringTemplateEngine, request context, константы контроллеров |
| service | FileItemService | MultipartFile, ID, DTO, User | FileItemDto/Page/Resource/void | FileItem/StoredObject repositories, FolderService, mapper, storage helpers, extractor, transaction manager, Files |
| service | FolderService | User/ID/name/parent | Folder/List/void | FolderRepository, FileItemRepository |
| service.sync | ChecksumSyncService, ChecksumExistsProperties | User + batch | existing/missing | FolderService, FileItemRepository, properties |
| service.storage | StorageProperties, StorageKeyGenerator, StoragePathResolver, FilenameSanitizer, FileContentDetector | имена/пути/checksum/Path | relative/absolute path, MIME | Filesystem API/Tika/config |
| service.metadata | FileMetadataExtractor, DrewFileMetadataExtractor, ExtractedFileMetadata | Path + MIME | optional extracted fields | metadata-extractor library |
| repository | 7 Spring Data interfaces | predicates/entities/pageable | rows/pages/projections | JPA/domain |
| model | 7 entities, 5 enums, EmailContext | persistence/state | domain fields | JPA, Hibernate timestamps; User также Spring Security |
| model.dto / mapper | 21 DTO; 3 MapStruct mapper interfaces | boundary/domain data | JSON forms, mapped entities | model, validation, Jackson |
| config / filter | SecurityConfig, EncoderConfig, MailConfig; 4 filters/handlers | HTTP, properties | security chain, beans, logging | service.UserService/JwtService, ErrorResponse |
| exception / dto | 17 custom RuntimeException classes, ErrorType, GlobalExceptionHandler, ErrorResponse/ErrorCode | исключения | selected HTTP errors | Spring MVC, validation, DAO exceptions |
| util | FileUtils, DateUtil, ConfigUtil | поток/даты | checksum/size/formats | JDK; FileUtils → FileSizeLimitExceededException |

[CONFIRMED] Состав и зависимости восстановлены по полям final, импортам и вызовам. Полный пофайловый указатель: 18-code-map.md.

~~~mermaid
flowchart TD
    Client[HTTP client] --> Log[HttpLoggingFilter]
    Log --> Security[SecurityFilterChain / JwtAuthenticationFilter]
    Security --> Controllers[7 Controllers]
    Security --> User[UserService]
    Security --> JWT[JwtService]
    Controllers --> User
    Controllers --> Files[FileItemService]
    Controllers --> Folders[FolderService]
    Controllers --> Sync[ChecksumSyncService]
    Controllers --> Mapper[DTO / MapStruct]
    User --> JWT
    User --> Codes[EmailRequestService]
    User --> Email[EmailService]
    Email --> Templates[Thymeleaf templates]
    Email --> SMTP[JavaMailSender / SMTP]
    Files --> Folders
    Sync --> Folders
    Files --> Storage[Storage helpers / FileUtils / Files]
    Files --> Metadata[Tika / Drew metadata extractor]
    Files --> Mapper
    User --> Repos[Spring Data repositories]
    JWT --> Repos
    Codes --> Repos
    Files --> Repos
    Folders --> Repos
    Sync --> Repos
    Repos --> DB[(PostgreSQL)]
    Storage --> Disk[(Local filesystem)]
~~~

## Проверка границ

[CONFIRMED] Контроллеры не вызывают repository, EntityManager или JdbcTemplate. FileController содержит fixed sort/PageRequest и сборку PageResponse, Content-Type/Content-Disposition download; UserController вручную создаёт UserDto; RootController собирает message. Это транспортная/представительская логика; правила duplicate, ownership, папок, паролей находятся в service. Источник: controller/*.java.

[CONFIRMED] Production-services работают с БД через repository, прямого SQL/EntityManager/JdbcTemplate не найдено. FileItemService управляет TransactionTemplate, остальные транзакционные операции используют @Transactional. JdbcTemplate найден в тестах для fixtures/TRUNCATE. Наличие программных транзакций не является обходом repository.

[CONFIRMED] Направление слоёв не строго однонаправленное: EmailService импортирует PasswordController/RegisterController для URL, SecurityConfig импортирует controller constants, domain.User реализует UserDetails, service.FileItemService возвращает HTTP Resource, config.filter обращается к UserService. Поэтому transport/security связаны с domain и сервисами.

[CONFIRMED] Циклических constructor-injection цепочек по доступным bean dependencies не найдено. [INFERRED] На уровне packages существует двусторонняя связь controller ↔ service из-за генерации email links. Это не доказательство ошибки старта Spring.

[CONFIRMED] UserService совмещает аккаунт/auth/registration/password orchestration; JwtService — криптографию, refresh persistence и отзыв. FileItemService совмещает file operations, disk compensation, metadata creation и DB transaction orchestration. Отдельной StorageService abstraction над upload/delete/copy нет.

[INCONSISTENCY] Константы срока email code продублированы в EmailRequest, ConfigUtil и числовых литералах checkAndGenerateCode(); сейчас везде 3 дня. ACTIVE_REQUESTS_MAX объявлен, но production flow не вызывает метод с ограничением. Источники: EmailRequest.java, ConfigUtil.java, EmailRequestService.java.

## Persistence и маппинг

[CONFIRMED] FileItemRepository использует EntityGraph(folder, folder.parent, storedObject, metadata) для карточек/списков, JPQL projection для checksum-list. FileItem.metadata — cascade ALL + orphanRemoval; остальные связи в основном LAZY, роли User — EAGER. open-in-view=false. В DTO не передаются user password, StoredObject ID/paths.

[CONFIRMED] FileItemMapper явно берёт checksum/size/MIME/type из StoredObject; FileItem.checksum служит отдельным persistence invariant. FolderMapper превращает parent.id в parentId; UserRegisterMapper отображает email/password. Обратный UserRegisterMapper.toUserRegisterDto() не вызывается production-кодом.

[RISK] Read-check-write нескольких repository вызовов без lock/@Version не гарантирует отсутствие конкурентных конфликтов. Это отдельно разобрано в 04-database.md и 16-known-gaps-and-risks.md; диаграмма не подразумевает общей транзакции disk+DB.

