# 02. Архитектура

## Стиль и модули

[CONFIRMED] settings.gradle объявляет один rootProject, без include subprojects. Единственная точка входа PhotoCloudServerApplication.main запускает SpringApplication. Компоненты сканируются под ru.tbcarus.photocloudserver.

[INFERRED] Архитектурный стиль — слоистый монолит REST + JPA + filesystem, с анемичными persistence entities и бизнес-операциями в сервисах. Формальных bounded contexts, портов/adapters или отдельного domain-модуля нет. «Object storage» в комментариях обозначает схему адресации локальных файлов, а не S3.

## Слои и зависимости

| Слой | Назначение, ключевые классы | Вход | Выход | Зависимости |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Bootstrap | PhotoCloudServerApplication | main args, Spring environment | ApplicationContext | Boot |
| [CONFIRMED] Controllers | Auth, Register, Password, User, Root, File, Folder | HTTP, DTO, principal | DTO/ResponseEntity/Resource | Services, FolderMapper, Spring Data PageRequest |
| [CONFIRMED] User/auth services | UserService, JwtService | Credentials, User, tokens | Tokens, User, mutations | Repositories, BCrypt, JJWT, email services |
| [CONFIRMED] Email | EmailRequestService, EmailService | User/type/code; request context | DB transitions, HTML SMTP | User/Email repositories, Encoder, Thymeleaf, JavaMail |
| [CONFIRMED] Files | FileItemService | MultipartFile, User, IDs/requests | FileItemDto, Resource, deletes | 2 repositories, FolderService, 5 storage components, extractor, TransactionManager |
| [CONFIRMED] Folders | FolderService | User, ids, names | Folder/List, mutations | FolderRepository, FileItemRepository |
| [CONFIRMED] Sync | ChecksumSyncService | ChecksumExistsRequest, User | existing/missing | FolderService, FileItemRepository, properties |
| [CONFIRMED] Persistence | 7 JpaRepository interfaces | IDs, JPQL/derived methods, entities | Entities/pages/projections | Spring Data JPA/Hibernate/PostgreSQL |
| [CONFIRMED] Entity model | User, RefreshToken, EmailRequest, Folder, StoredObject, FileItem, FileMetadata | ORM fields/builders | State and relations | JPA, Hibernate, Lombok; User also Security UserDetails |
| [CONFIRMED] DTO/mappers | 21 DTO; FileItemMapper, FolderMapper, UserRegisterMapper | JSON/entities | Client schema/entities | Validation, Jackson, MapStruct |
| [CONFIRMED] Storage | StorageProperties, StoragePathResolver, StorageKeyGenerator, FilenameSanitizer, FileContentDetector | Paths, names, hashes, disk file | Safe path/name, MIME | java.nio, UUID, Tika |
| [CONFIRMED] Metadata | FileMetadataExtractor, DrewFileMetadataExtractor, ExtractedFileMetadata | Temp path + MIME | Nullable metadata | metadata-extractor |
| [CONFIRMED] Security | SecurityConfig, JwtAuthenticationFilter, JSON entry/denied handlers | Bearer header | principal or 401/403 | UserService, JwtService, Jackson |
| [CONFIRMED] Cross-cutting | GlobalExceptionHandler, HttpLoggingFilter | Exceptions/HTTP streams | ErrorResponse/logs | Spring MVC/Servlet |
| [CONFIRMED] Utilities | FileUtils; ConfigUtil; DateUtil | Stream/time/constants | Hash/count/date helpers | JDK; DateUtil вызывается из templates/fragments/footer.html |

## Диаграмма

~~~mermaid
flowchart TD
  C[HTTP client] --> L[HttpLoggingFilter]
  L --> S[SecurityConfig / JwtAuthenticationFilter]
  S --> UC[Auth / Register / Password controllers]
  S --> PC[User / Root controllers]
  S --> FC[FileController]
  S --> FOC[FolderController]
  S --> US[UserService]
  UC --> US
  UC --> ER[EmailRequestService]
  US --> J[JwtService]
  US --> ER
  US --> EM[EmailService]
  EM --> SMTP[Thymeleaf / JavaMail / SMTP]
  FC --> FI[FileItemService]
  FC --> SY[ChecksumSyncService]
  FI --> FO[FolderService]
  SY --> FO
  FOC --> FO
  FI --> ST[Storage helpers / FileUtils]
  FI --> MD[Drew metadata extractor]
  ST --> FS[Local filesystem]
  MD --> FS
  US --> R[JpaRepository layer]
  ER --> R
  J --> R
  FI --> R
  SY --> R
  FO --> R
  R --> DB[(PostgreSQL)]
~~~

## Проверка распределения ответственности

[CONFIRMED] Контроллеры не выполняют SQL и файловые записи. Однако FileController задаёт сортировку, строит PageRequest/PageResponse и Content-Disposition/MIME download; UserController вручную конструирует UserDto; RegisterController напрямую вызывает EmailRequestService. Это фактическое размещение presentation-логики и оркестрации. Проверки ownership, дублей, структуры дерева и транзакции находятся в сервисах.

[CONFIRMED] В production нет EntityManager, JdbcTemplate, native SQL в сервисах. Доступ к БД проходит через repositories; использование TransactionTemplate в FileItemService управляет транзакцией, а не обходит repository. JdbcTemplate найден только в integration-test setup/assertions.

[CONFIRMED] FileItemService самостоятельно управляет физическим IO, DB transaction и компенсацией. Отдельного StorageRepository/StorageService интерфейса нет. Metadata extractor имеет интерфейс с одной реализацией.

[INFERRED] Цикл инъекций Spring beans не обнаружен: FileItemService → FolderService → repositories; UserService → JwtService/EmailRequestService/EmailService → repositories/Encoder; обратной инъекции нет. На уровне packages обратные зависимости присутствуют: EmailService импортирует controller constants; FileItemRepository импортирует DTO FileChecksumDto; entities User/Role зависят от Spring Security. Поэтому package graph не является строгим DAG слоёв.

[CONFIRMED] MapStruct формирует spring beans на compile time; generated implementations не являются отдельной ручной архитектурой. FileItemMapper получает physical fields из storedObject, folderId из folder, metadata из связи. UserRegisterMapper имеет обратный метод toUserRegisterDto без найденного production caller.

## Пересечения ответственности и риски границ

| Категория | Наблюдение |
| --- | --- |
| [CONFIRMED] | DTO validation + сервисная нормализация + DB constraints совместно контролируют имена/идентификаторы; это разные уровни защиты |
| [INCONSISTENCY] | Срок email-кода и лимит заявлены и в EmailRequest, и в ConfigUtil; фактический rate-limit метод использует литералы 3 |
| [RISK] | UserService совмещает Users, authentication, registration и recovery; изменения одного flow затрагивают общие зависимости |
| [RISK] | Обработчик filesystem cleanup охватывает также mapping после commit upload: runtime-ошибка mapper может удалить final при уже committed DB |
| [RISK] | FolderService.getFolderForUser возвращает LAZY parent; FolderController маппит getOrCreateRoot/getChildren после service transaction. Корректность конкретных access-path при open-in-view=false зависит от загруженных полей; FileItemRepository явно использует EntityGraph |
| [CONFIRMED] | @EnableMethodSecurity включён, но @PreAuthorize/@Secured/@RolesAllowed в production не найдены |

## Источники

[settings.gradle](C:/projects/photo-cloud-server/settings.gradle), [PhotoCloudServerApplication.java](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/PhotoCloudServerApplication.java), [пакет service](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [repository](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository), [mappers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper).
