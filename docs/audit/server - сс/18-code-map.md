# 18. Code Map

> Карта репозитория для быстрой навигации. Пакетный корень: `ru.tbcarus.photocloudserver`, каталог `src/main/java/ru/tbcarus/photocloudserver/`.

---

## 0. Структура репозитория верхнего уровня

```
photo-cloud-server/
├── build.gradle              Spring Boot 3.4.4, Java 17, все зависимости
├── settings.gradle           один модуль
├── gradle/, gradlew*         Gradle 8.13 wrapper
├── src/main/java/...         исходный код (100 файлов)
├── src/main/resources/
│   ├── application.yml       единственная основная конфигурация
│   ├── .env.local/.docker    секреты (в git не попадают, но лежат в resources)
│   ├── db/changelog/         Liquibase: master.yml + table/01..14-*.sql
│   ├── templates/            Thymeleaf: email/*.html, fragments/*.html
│   └── static/css/style.css
├── src/test/java/...         7 тестовых классов
├── src/test/resources/application-test.yml
├── db/audit/                 ручной SQL-скрипт аудита дублей
├── docs/                     api-*-contract.md, application-overview.md, http-tests/
│   └── audit/server/         ← этот аудит
├── storage/                  локальные данные разработки (в .gitignore)
├── README.md, README-description.md, TODO.txt, HELP.md
└── .idea/                    настройки IDE, сохранённые HTTP-запросы
```

**Быстрая навигация по задачам:**

| Задача | Куда смотреть |
| --- | --- |
| Изменить API | `controller/` + соответствующий `docs/api-*-contract.md` |
| Изменить схему БД | добавить `src/main/resources/db/changelog/table/NN-*.sql` (номер +1) |
| Изменить логику загрузки | `service/FileItemService.uploadFile()` |
| Изменить правила папок | `service/FolderService` |
| Изменить auth | `service/JwtService` + `service/UserService` + `config/SecurityConfig` |
| Изменить формат ошибок | `exception/GlobalExceptionHandler` + `exception/dto/` |
| Изменить путь/имя файла на диске | `service/storage/StorageKeyGenerator` + `FilenameSanitizer` |
| Добавить конфигурацию | `application.yml` + `@ConfigurationProperties`-класс |

---

## 1. `ru.tbcarus.photocloudserver` (корень)

**Назначение:** точка входа.

- `PhotoCloudServerApplication` — `@SpringBootApplication`, только `main()`. Дополнительных `@Enable*` нет.

---

## 2. Package `config`

**Назначение:** определение бинов и конфигурация Spring Security / почты.

**Ключевые классы:**

- `SecurityConfig` — **самый важный файл для понимания доступа.** Задаёт `SessionCreationPolicy.STATELESS`, permitAll-список (11 URL, собранных из констант контроллеров + 3 springdoc-шаблона), `anyRequest().authenticated()`, обработчики 401/403, регистрирует `JwtAuthenticationFilter` перед `UsernamePasswordAuthenticationFilter`, отключает CSRF. `@EnableMethodSecurity` включён, но нигде не используется.
- `EncoderConfig` — единственный бин: `BCryptPasswordEncoder`.
- `MailConfig` — `JavaMailSender` (SMTP-параметры, `mail.debug=true`) и `SpringTemplateEngine` для писем.

**Связи:** зависит от `config.filter.*` и от констант в `controller.*` (обратная зависимость слоя).

---

## 3. Package `config.filter`

**Назначение:** HTTP-фильтры и обработчики отказа в доступе.

**Ключевые классы:**

- `JwtAuthenticationFilter` — извлекает `Bearer`-токен, проверяет `token_type == ACCESS`, загружает `UserDetails`, заполняет `SecurityContext`. При `ExpiredJwtException`/`JwtException` очищает контекст и делегирует `401`.
- `JsonAuthenticationEntryPoint` — `401` в формате `ErrorResponse` (`code: UNAUTHORIZED`).
- `JsonAccessDeniedHandler` — `403` в формате `ErrorResponse` (`code: FORBIDDEN`). Практически недостижим.
- `HttpLoggingFilter` — `@Order(HIGHEST_PRECEDENCE)`, логирует все запросы и ответы с телами. **Источник риска D1/E5** из `16-known-gaps-and-risks.md`.

**Связи:** `JwtAuthenticationFilter` → `JwtService`, `UserService`, `JsonAuthenticationEntryPoint`.

---

## 4. Package `controller`

**Назначение:** REST-слой. Все контроллеры аннотированы `@Tag`/`@Operation` (springdoc) и объявляют пути как `public static final String` — эти константы переиспользуются в `SecurityConfig`, `EmailService` и тестах.

**Ключевые классы:**

| Класс | Base URL | Что делает |
| --- | --- | --- |
| `ApiPaths` | — | единственная константа `API_V1 = "/api/v1"` |
| `RootController` | `/api/v1` | 2 диагностических endpoint'а для проверки связи Android-клиентом |
| `RegisterController` | `/api/v1/auth/register` | регистрация, подтверждение, resend (`501`) |
| `AuthController` | `/api/v1/auth` | login, logout ×3, refresh-token |
| `PasswordController` | `/api/v1/auth/password` | reset request/confirm + 3 заглушки (`501`). `@Validated` на классе — для валидации `@RequestParam` |
| `UserController` | `/api/v1/profile` | GET профиля + 3 заглушки (`501`). Сам конструирует `UserDto` |
| `FolderController` | `/api/v1/folders` | 6 операций с деревом; вызывает `FolderMapper` напрямую |
| `FileController` | `/api/v1/files` | 11 маппингов; строит `Sort`/`PageResponse`; самый большой контроллер |

**Связи:** контроллеры → сервисы. `FolderController` дополнительно → `FolderMapper`.

---

## 5. Package `service`

**Назначение:** вся бизнес-логика.

**Ключевые классы:**

- **`FileItemService`** (487 строк, 11 зависимостей) — **центральный класс системы.** Содержит upload pipeline (temp → checksum → MIME → EXIF → выбор папки → проверка дубля → move → транзакция БД), а также rename/move/copy/delete/download/list. Единственное место, где используется программный `TransactionTemplate` — чтобы файловые операции не удерживали соединение с БД. Здесь же вся логика cleanup при сбоях.
- **`FolderService`** — все инварианты дерева папок: ленивое создание `ROOT`/`Camera`/`Files` (race-safe), запрет операций над системными папками, защита от циклов, уникальность имён, удаление только пустых папок. Полностью `@Transactional`.
- **`UserService`** (`implements UserDetailsService`) — регистрация, login, logout ×3, refresh, forgot/reset password, `loadUserByUsername()`. Совмещает три ответственности (см. `02-architecture.md` §4.5).
- **`JwtService`** — генерация access/refresh, парсинг claim'ов, валидация, отзыв (`revoke`, `revokeAll`, `revokeOther` и их «owned»-варианты). TTL захардкожены как поля класса.
- **`EmailRequestService`** — генерация одноразовых кодов, подтверждение регистрации, сброс пароля. Использует `jakarta.transaction.Transactional` (в отличие от остального кода).
- **`EmailService`** — сборка `EmailContext`, рендеринг Thymeleaf, отправка через `JavaMailSender`. Строит ссылку из `HttpServletRequest` через `RequestContextHolder`.

**Связи:** `FileItemService` → `FolderService` (односторонняя); `UserService` → `JwtService`, `EmailRequestService`, `EmailService`; все → соответствующие репозитории.

---

## 6. Package `service.storage`

**Назначение:** всё, что касается физического размещения файлов. Классы не зависят от домена и легко тестируются.

| Класс | Что делает |
| --- | --- |
| `StorageProperties` | `@ConfigurationProperties("storage")`: `root`, `maxFileSizeBytes`, `tempDir`, `physicalFilename.originalNameMaxLength` |
| `StorageKeyGenerator` | `generateFilePath(userId, checksum)` → `users/{id}/objects/{cs0:2}/{cs2:4}`; `generateFilename(name, mime)` → `{name}_{uuid}.{ext}` |
| `FilenameSanitizer` | санитизация имён, извлечение и нормализация расширения, ограничение длины, сборка физического имени |
| `StoragePathResolver` | `(filePath, filename)` → абсолютный `Path`; **защита от path traversal** |
| `FileContentDetector` | обёртка над `org.apache.tika.Tika` — определение MIME по содержимому |

**Связи:** используются только `FileItemService`.

---

## 7. Package `service.metadata`

**Назначение:** извлечение технических метаданных.

| Класс | Что делает |
| --- | --- |
| `FileMetadataExtractor` | интерфейс — **единственная точка расширения по интерфейсу во всём проекте** |
| `DrewFileMetadataExtractor` | реализация на metadata-extractor: размеры, камера, объектив, выдержка, ISO, фокусное, GPS, дата съёмки. **Только для `image/*`**; любое исключение поглощается с `log.warn` |
| `ExtractedFileMetadata` | immutable `@Value @Builder`; метод `hasMetadataFields()` определяет, создавать ли строку `file_metadata` |

---

## 8. Package `service.sync`

**Назначение:** серверная часть протокола синхронизации (пока только pre-check).

| Класс | Что делает |
| --- | --- |
| `ChecksumSyncService` | проверка batch-лимита → проверка владения папкой → нормализация (lowercase + `LinkedHashSet`) → запрос в БД → разложение на `existing`/`missing` с сохранением порядка |
| `ChecksumExistsProperties` | `@ConfigurationProperties("sync.checksum-exists")`: `maxBatchSize = 500` |

**Связи:** → `FileItemRepository`, `FolderService`.

---

## 9. Package `repository`

**Назначение:** доступ к данным. Только интерфейсы Spring Data JPA, без реализаций и без `EntityManager`.

| Интерфейс | Особенности |
| --- | --- |
| `FileItemRepository` | самый насыщенный: `@EntityGraph` на всех методах чтения (борьба с N+1 при `open-in-view: false`), JPQL-проекция в `FileChecksumDto`, запрос `findExistingChecksumsInFolder` |
| `FolderRepository` | JPQL-запросы с `lower(name)` для case-insensitive поиска; `findRootByUserId` |
| `UserRepository` | `findByEmailIgnoreCase` (JPQL `WHERE u.email = LOWER(:email)`) |
| `RefreshTokenRepository` | **ID-тип `Integer`** при `Long id` в сущности; `findByToken` (без индекса в БД) |
| `EmailRequestRepository` | **ID-тип `Integer`** при `Long id`; часть методов [UNUSED] |
| `StoredObjectRepository` | один метод, помеченный TODO и [UNUSED] |
| `FileMetadataRepository` | **[UNUSED]** — пустой интерфейс, нигде не инжектится |

---

## 10. Package `model`

**Назначение:** JPA-сущности и enum'ы.

| Класс | Роль |
| --- | --- |
| `User` | сущность **и** `UserDetails` (Spring Security principal) |
| `Folder` | узел дерева, self-referencing `parent` |
| `FileItem` | логическая запись файла; денормализованный `checksum`; неиспользуемый `deletedAt` |
| `StoredObject` | физический объект; наружу не отдаётся |
| `FileMetadata` | опциональные EXIF-данные, `@OneToOne` к `FileItem` |
| `RefreshToken` | серверная запись о выданном refresh; связана с `User` **строкой email**, не FK |
| `EmailRequest` | одноразовый код; содержит бизнес-константы и методы `isActive()`/`isExpired()` |
| `Role` | `USER`, `ADMIN`; `implements GrantedAuthority` |
| `FolderType` | `ROOT`, `CAMERA`, `FILES`, `USER` |
| `FileType` | 6 категорий + статический `fromMimeType()` |
| `TokenType` | `TOKEN_TYPE` (имя claim'а) + `ACCESS`/`REFRESH` (значения) |
| `EmailRequestType` | `ACTIVATE`, `PASSWORD_RESET` — с русскими заголовками писем |
| `EmailContext` | **не сущность** — транспортный объект для `EmailService` |

---

## 11. Package `model.dto` и `model.dto.mapper`

**Назначение:** контракт с клиентом.

**Request DTO (все — `record`):** `LoginRequest`, `LogoutRequest`, `RefreshRequest`, `RegisterRequest`, `PasswordResetConfirmRequest`, `CreateFolderRequest`, `RenameFolderRequest`, `MoveFolderRequest`, `RenameFileRequest`, `MoveFileRequest`, `CopyFileRequest`, `ChecksumExistsRequest`.

**Response DTO:** `LoginResponse`, `RefreshResponse`, `UserDto`, `ChecksumExistsResponse` (`record`); `FileItemDto`, `FileMetadataDto`, `FolderDto`, `FileChecksumDto`, `PageResponse<T>` (Lombok-классы).

**Мапперы (MapStruct, `componentModel = SPRING`):**
- `FileItemMapper` — склеивает `FileItem` + `StoredObject` в один DTO; отдельно маппит `FileMetadata`;
- `FolderMapper` — `Folder` → `FolderDto`;
- `UserRegisterMapper` — `RegisterRequest` → `User` (обратный метод [UNUSED]).

**Важно:** маппера `User` → `UserDto` **нет** — `UserController` конструирует DTO вручную.

---

## 12. Package `exception` и `exception.dto`

**Назначение:** доменные исключения и единый формат ошибок.

- `GlobalExceptionHandler` (`@RestControllerAdvice`) — 23 обработчика; **не логирует исключения** и **не имеет catch-all для `Exception`**.
- 17 классов исключений, все от `RuntimeException`; конвенции конструкторов несогласованы.
- `ErrorType` — enum с русскими сообщениями (частично [UNUSED]).
- `exception.dto.ErrorResponse` — `{id, code, message, fieldErrors}`.
- `exception.dto.ErrorCode` — 16 значений (`INTERNAL_ERROR` [UNUSED]).

---

## 13. Package `util`

| Класс | Статус |
| --- | --- |
| `FileUtils` | **используется** — `writeAndCalculateSHA256()` — потоковая запись с одновременным подсчётом SHA-256 и контролем размера; record `StreamedFileInfo` |
| `ConfigUtil` | частично используется (только `DEFAULT_EXPIRED_DAYS`) |
| `DateUtil` | **[UNUSED]** в Java-коде; два метода вызываются из Thymeleaf-фрагмента `footer.html` через SpEL `T(...)`. Комментарии в классе — наследие другого проекта |

---

## 14. Ресурсы

| Путь | Содержимое |
| --- | --- |
| `application.yml` | вся основная конфигурация (30 параметров) |
| `db/changelog/db.changelog-master.yml` | `includeAll` по каталогу `table` |
| `db/changelog/table/01..14-*.sql` | Liquibase formatted SQL; 04–07 относятся к удалённой `media_file` |
| `templates/email/confirmationTemplate.html` | письмо активации |
| `templates/email/passwordResetTemplate.html` | письмо сброса пароля; **брендинг «Spending web App»** |
| `templates/fragments/headTag.html` | [UNUSED] — подключён закомментированной строкой |
| `templates/fragments/footer.html` | вызывает `DateUtil` через SpEL |
| `static/css/style.css` | ссылается из писем (во внешней почте не применится) |
| `.env.local`, `.env.docker` | секреты; Spring их не читает |

---

## 15. Тесты

| Путь | Роль |
| --- | --- |
| `integration/AbstractIntegrationTest` | база: Testcontainers, MockMvc, TRUNCATE между тестами, временное хранилище, хелперы, логирование с маскированием |
| `integration/FileFlowIntegrationTest` | 28 тестов — весь файловый flow |
| `integration/AuthErrorHandlingIntegrationTest` | 24 теста — auth и контракт ошибок |
| `integration/FolderApiIntegrationTest` | 12 тестов — инварианты дерева |
| `integration/ProfileIntegrationTest` | 1 тест — поля профиля |
| `service/FileItemServiceTest` | 2 unit-теста — откат файла при сбое БД и обработка гонки |
| `service/sync/ChecksumSyncServiceTest` | 2 unit-теста — нормализация и порядок проверок |
| `PhotoCloudServerApplicationTests` | `contextLoads` |

---

## 16. Граф ключевых зависимостей (кто кого вызывает)

```
JwtAuthenticationFilter ──► JwtService ──► RefreshTokenRepository
                        └─► UserService ──► UserRepository
                                        ├─► JwtService
                                        ├─► EmailRequestService ──► EmailRequestRepository
                                        └─► EmailService ──► JavaMailSender + Thymeleaf

FileController ──► FileItemService ──┬─► FileItemRepository
               └─► ChecksumSyncService   ├─► StoredObjectRepository
                        │                 ├─► FolderService ──► FolderRepository
                        │                 ├─► StorageKeyGenerator ──► FilenameSanitizer
                        │                 ├─► StoragePathResolver
                        │                 ├─► FileContentDetector (Tika)
                        │                 ├─► FileMetadataExtractor (metadata-extractor)
                        │                 ├─► FileUtils (SHA-256)
                        │                 └─► PlatformTransactionManager
                        ├─► FolderService
                        └─► FileItemRepository

FolderController ──► FolderService ──┬─► FolderRepository
                 └─► FolderMapper     └─► FileItemRepository (проверка «папка пуста»)
```

---

## 17. Пять файлов, которые нужно прочитать первыми

**[INFERRED]** Для быстрого входа в проект:

1. **`service/FileItemService.java`** — ядро продукта: upload pipeline, обработка сбоев, все файловые операции.
2. **`config/SecurityConfig.java`** — что открыто, что закрыто, как устроена аутентификация.
3. **`db/changelog/table/10-replace-media-file-with-file-model.sql`** и **`14-file-item-user-folder-checksum.sql`** — ключевой рефакторинг модели и текущее определение «дубликата».
4. **`service/FolderService.java`** — все инварианты дерева папок в одном месте.
5. **`docs/application-overview.md`** — авторская карта реализации; в целом точна, расхождения перечислены в `05-api.md` §11.

Дополнительно: **`TODO.txt`** даёт представление о том, какие решения автор считал незакрытыми (особенно пункты 2.5, 7, 8, 9, 18, 20, 21).
