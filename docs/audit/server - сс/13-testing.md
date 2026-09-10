# 13. Testing

---

## 1. Состав тестов

**[CONFIRMED]** 7 тестовых классов, ~70 тестовых методов.

| Файл | Тип | Кол-во тестов | Инфраструктура |
| --- | --- | --- | --- |
| `integration/AbstractIntegrationTest.java` | базовый класс | 0 | `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Testcontainers`, `@DirtiesContext(AFTER_CLASS)` |
| `integration/FileFlowIntegrationTest.java` | integration | **28** | MockMvc + Testcontainers + реальная ФС |
| `integration/AuthErrorHandlingIntegrationTest.java` | integration | **24** | MockMvc + Testcontainers |
| `integration/FolderApiIntegrationTest.java` | integration | **12** | MockMvc + Testcontainers |
| `integration/ProfileIntegrationTest.java` | integration | **1** | MockMvc + Testcontainers |
| `PhotoCloudServerApplicationTests.java` | smoke | **1** (`contextLoads`) | наследует `AbstractIntegrationTest` |
| `service/FileItemServiceTest.java` | unit | **2** | Mockito, реальные `FilenameSanitizer`/`StorageKeyGenerator`/`StoragePathResolver`/`Tika`, временный каталог |
| `service/sync/ChecksumSyncServiceTest.java` | unit | **2** | чистый Mockito, без Spring |

**[CONFIRMED]** Соотношение: **65 integration / 4 unit** — тестовая пирамида перевёрнута, но для проекта такого размера это осознанный выбор, дающий высокую достоверность.

---

## 2. Тестовая инфраструктура

**[CONFIRMED]** `AbstractIntegrationTest` — качественно сделанная база:

| Возможность | Реализация |
| --- | --- |
| Реальная БД | Testcontainers `postgres:16-alpine`, схема через тот же Liquibase-changelog |
| Изоляция | `@BeforeEach cleanupDbAndStorage()` → `TRUNCATE ... RESTART IDENTITY CASCADE` по 8 таблицам + очистка каталога хранилища |
| Изолированное хранилище | `Files.createTempDirectory("photo-cloud-server-it-")`, подставляется через `@DynamicPropertySource`; удаляется в `@AfterAll` |
| Заниженный лимит размера | `storage.max-file-size-bytes = 1024` — позволяет тестировать `413` на маленьких файлах |
| Хелперы | `createUser()`, `loginAndGetAccessToken()`, `upload()`, `sha256()`, `findFileItem()`, `deletePhysicalFile()`, `storageFileCount()`, `tempFileCount()`, `storedObjectPath()` |
| Логирование обмена | `perform()` печатает запрос/ответ **с маскированием** `accessToken`/`refreshToken` и заголовка `Authorization` |
| Проверка ФС | `storageFileCount()` (исключая `tmp`) и `tempFileCount()` — позволяют утверждать «мусора не осталось» |

**[CONFIRMED]** Маскирование секретов в тестовом выводе (`maskSensitiveJson`, `maskAuthorization`) реализовано аккуратно — что делает ещё более заметным его отсутствие в production-фильтре `HttpLoggingFilter`.

**[CONFIRMED][RISK]** `@DirtiesContext(AFTER_CLASS)` пересоздаёт Spring-контекст после каждого тестового класса — это заметно замедляет прогон (5 классов × полный контекст) и не обязательно, поскольку `@BeforeEach` уже чистит состояние.

---

## 3. Покрытие по подсистемам

| Subsystem | Tests found | Coverage by scenario | Missing critical scenarios |
| --- | --- | --- | --- |
| **Upload pipeline** | 12 (`FileFlowIntegrationTest`) + 2 unit | новый файл, дубль в той же папке, тот же checksum в другой папке, upload с `folderId`, default-папки, конфликт имени, CAMERA-исключение, streaming без `getBytes()`, превышение лимита, сбой потока, MIME по содержимому, длинное имя, fallback `capturedAt`, сбой EXIF, откат при падении БД, гонка по checksum | параллельный upload из нескольких потоков (гонка смоделирована моками, не реально); поведение при заполненном диске; `AtomicMoveNotSupportedException` fallback; upload файла ровно на границе лимита |
| **File CRUD** | 10 | list (пагинация, сортировка, по папке, только свои), get, download (байты и Content-Type), rename (+конфликт), move, copy (+2 сценария конфликта), delete (владелец/не владелец), отсутствующий физический файл | **move с конфликтом checksum в целевой папке** (даст `500`); download несуществующего `id`; `size`-параметр вне разумных границ; Content-Disposition с не-ASCII/кавычкой в имени |
| **Folder API** | 12 (`FolderApiIntegrationTest`) | ленивое создание ROOT, запрет delete/move/rename системных папок, запрет создания внутри CAMERA/FILES, дубль имени (case-insensitive), зарезервированные имена, одинаковые имена в разных родителях, move в себя/в потомка, чужая папка, удаление только пустой, children только свои, upload использует default-папки | глубокая вложенность; `parentId` = чужой ROOT при создании; конкурентное создание одноимённых папок; переименование в имя, отличающееся только регистром |
| **Auth / errors** | 24 (`AuthErrorHandlingIntegrationTest`) | login (успех, неизвестный email, неверный пароль, banned, disabled, `lastLoginAt`), register (валидация, дубль), confirm (неверный/использованный/истёкший код), password reset (валидация, длина, успех, отказ от legacy-контракта), refresh (blank, неизвестный, revoked), logout/logout-others (blank, чужой, неизвестный, свой), защищённый endpoint без токена и с невалидным JWT | **истёкший access token** (нет теста с реальным expired JWT); `logout-all`; refresh для banned/disabled пользователя; JWT c `token_type=REFRESH` на защищённом endpoint; access token удалённого пользователя (даёт `500`) |
| **Checksum sync** | 8 integration + 2 unit | требование авторизации, валидация тела (5 случаев), чужая/несуществующая папка, превышение batch, existing/missing только для своих, дубликаты и uppercase, изоляция по папке и пользователю, нормализация и порядок, проверка лимита до обращения к БД | batch ровно на границе (500); очень длинный список валидных checksum (производительность) |
| **Profile** | 1 (`ProfileIntegrationTest`) | поля DTO, отсутствие `firstName`/`lastName`, наличие `lastLoginAt` | отсутствие `password` в ответе (**не проверяется явно**); `401` без токена |
| **Security boundaries** | ~6 (распределены) | доступ к чужим файлам/папкам → `404`, checksum-изоляция, ownership refresh-токенов | ролевые проверки (их нет в коде); path traversal (невозможен через API); заголовки безопасности |
| **File storage (ФС)** | ~8 | физический путь, безопасное имя, ограничение 255 символов, отсутствие temp-мусора, копия при `copy`, удаление файла при delete, сохранение файла при не-владельце | orphan-файлы; сбой при удалении; разные ФС для temp и root |
| **Error contract** | ~10 | структура `ErrorResponse` (`id`, `code`, `message`, `fieldErrors`), коды статусов | ответ на **необработанное** исключение (Spring-дефолт, другой формат); `501`-заглушки |
| **Email** | **0** | — | **весь email-flow не покрыт**: генерация письма, ссылка, шаблон, поведение при `MessagingException`, NPE в catch |
| **Mappers** | 0 прямых | косвенно через DTO в integration-тестах | `FileItemMapper` для `fNumber`; `UserRegisterMapper` |
| **Background jobs** | — | нечего тестировать | — |
| **`501`-заглушки** | **0** | — | ни один из 7 endpoint'ов не проверен |
| **Конфигурация** | 0 | — | `StorageProperties`/`ChecksumExistsProperties` не тестируются напрямую |
| **Liquibase-миграции** | косвенно (схема применяется в каждом тесте) | применимость changelog | миграция данных 09 и 11 на непустой БД **не тестируется** |

---

## 4. Наиболее ценные тесты

**[CONFIRMED]** Тесты, покрывающие нетривиальные инварианты:

| Тест | Что доказывает |
| --- | --- |
| `uploadDoesNotCallMultipartFileGetBytes` | использует `NoGetBytesMultipartFile`, бросающий исключение в `getBytes()` — гарантирует, что файл не читается в память целиком |
| `finalFileIsRemovedWhenDatabaseSaveFailsAfterMove` | откат физического файла при сбое БД |
| `raceConditionRemovesCurrentFinalFileAndReturnsExistingFileItem` | обработка гонки по checksum |
| `uploadSameContentIntoDifferentFolderCreatesNewStoredObjectAndFileItem` | фиксирует ключевое архитектурное решение о дедупликации |
| `duplicateNameConflictsOutsideCameraAndCameraIsIdempotentPerChecksum` | различие правил для CAMERA и остальных папок |
| `mimeTypeIsDetectedFromContentInsteadOfMultipartHeader` | доверие содержимому, а не заголовку клиента |
| `nonOwnerDeleteRemovesOnlyFileItemAndKeepsStoredObjectAndPhysicalFile` | заготовка под sharing |
| `checkExistingRejectsBatchOverLimitBeforeFolderAndRepositoryCalls` | порядок проверок (защита от лишней нагрузки) |
| `passwordResetConfirmRejectsLegacyQueryParamContract` | явная фиксация отказа от старого контракта |
| `foreignFolderCannotBeUsedForListUploadCopyOrMove` | сквозная проверка ownership по 4 операциям |

---

## 5. Пробелы, наиболее значимые для качества

**[CONFIRMED]** Ранжировано по риску:

| # | Пробел | Почему важно |
| --- | --- | --- |
| 1 | **`move` с конфликтом checksum** не тестируется | это единственный известный путь к `500` в штатном сценарии |
| 2 | **Истёкший access token** не тестируется реальным expired JWT | ветка `ExpiredJwtException` в `JwtAuthenticationFilter` не покрыта |
| 3 | **Access token удалённого пользователя** | ведёт к `500` из-за `Optional.get()` |
| 4 | **refresh для banned/disabled** | подтвердил бы, что забаненный продолжает получать токены |
| 5 | **Email-flow полностью не покрыт** | включая NPE в `catch (MessagingException)` |
| 6 | **`logout-all`** не покрыт (в отличие от `logout` и `logout-others`) | и именно там не заполняется `revokedAt` |
| 7 | **Формат ответа при необработанном исключении** | клиент получит другой JSON, тест это не зафиксирует |
| 8 | **Отсутствие `password` в `/profile`** явно не проверяется | регрессия была бы утечкой хеша |
| 9 | **`501`-заглушки** не покрыты | их случайное изменение не будет замечено |
| 10 | **Миграции данных (09, 11) на непустой БД** | тесты всегда стартуют с пустой схемы; сложный regexp-backfill в migration 11 не проверен |
| 11 | **Реальная конкурентность** | гонки смоделированы моками, многопоточных тестов нет |
| 12 | **Ограничение `size` в `GET /files`** | нет теста, который зафиксировал бы желаемое поведение |

---

## 6. Инструменты и метрики покрытия

**[CONFIRMED]**

| Инструмент | Настроен? |
| --- | --- |
| JaCoCo / Cobertura | **нет** — в `build.gradle` плагина покрытия нет |
| Отчёты покрытия | нет |
| Мутационное тестирование (PIT) | нет |
| ArchUnit (проверки архитектуры) | нет |
| Статический анализ (SpotBugs, PMD, Checkstyle, SonarQube) | нет |
| CI-прогон тестов | **нет** — каталога `.github` не существует |
| Contract-тесты (Spring Cloud Contract, Pact) | нет |
| Нагрузочные тесты (JMeter, Gatling) | нет |

**[CONFIRMED]** `build.gradle` настраивает только `useJUnitPlatform()` и `testLogging { showStandardStreams = true }`.

**[CONFIRMED]** Процент покрытия не вычисляется и не может быть приведён в этом отчёте без внедрения инструмента (что выходит за рамки аудита «не изменять проект»).

---

## 7. Ручные smoke-тесты

**[CONFIRMED]** `docs/http-tests/`:

| Файл | Состояние |
| --- | --- |
| `api-smoke-tests.http` | 7.8 КБ, IntelliJ HTTP Client; покрывает root/test, register (3), auth (5), password (5), profile (4), files (6) |
| `api-curl-examples.md` | **[INCONSISTENCY]** 197 байт — обрывается на заголовке `## RootController`, примеров нет |
| `README.md` | подробная инструкция по запуску, список публичных/защищённых endpoint'ов |

**[CONFIRMED][INCONSISTENCY]** `api-smoke-tests.http` **не покрывает**: все 6 folder-endpoint'ов, `POST /files/upload`, `PATCH /files/{id}`, `POST /files/{id}/move`, `POST /files/{id}/copy` — хотя README файла утверждает, что там «запросы для всех endpoint'ов, которые сейчас объявлены в контроллерах».

**[CONFIRMED]** В `.idea/httpRequests/` сохранены 28 записей реальных ответов от 2026-05-12 (включая коды `200`, `401`, `404`) — артефакты ручного тестирования, не автоматизированные тесты.

---

## 8. Качество тестового кода

**[CONFIRMED]** Сильные стороны:

- Имена тестов описывают проверяемое поведение целиком (`uploadSameContentIntoDifferentFolderCreatesNewStoredObjectAndFileItem`).
- Проверяется не только HTTP-ответ, но и состояние БД **и** файловой системы одновременно.
- Есть специальные тестовые дублёры: `NoGetBytesMultipartFile`, `FailingInputStreamMultipartFile` — для проверки поведения, недостижимого обычными средствами.
- Тесты негативных сценариев доминируют над позитивными — редкая и полезная особенность.
- Комментарии в тестах на русском поясняют намерение (например, `// Отсутствует folderId → 400`).

**[CONFIRMED]** Слабые стороны:

- `@DirtiesContext(AFTER_CLASS)` без явной необходимости.
- В `FileFlowIntegrationTest` некоторые тесты вызывают `fileItemService` напрямую в обход MockMvc — смешение уровней.
- `ChecksumSyncServiceTest` использует полностью квалифицированные вызовы `org.mockito.Mockito.verify(...)` вместо статического импорта.
- Нет параметризованных тестов (`@ParameterizedTest`) там, где проверяются наборы похожих случаев (например, 5 подряд идущих проверок валидации в одном методе `checksumExistsValidatesRequestBody`) — при падении сложно понять, какой именно случай сломался.
