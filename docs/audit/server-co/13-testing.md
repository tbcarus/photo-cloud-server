# Существующие тесты и границы проверки

## Что найдено и что запускалось

[CONFIRMED — test source] 8 Java test-файлов: abstract support + 7 классов с суммарно 88 методами @Test. 4 unit tests в 2 классах; 84 integration/context tests в 5 классах. Parameterized tests, Jacoco/coverage configuration, load/race test runner не найдены в build.gradle и src/test.

**Нового запуска тестов в этом аудите не было.** Задание разрешает только новые отчёты и запрещает изменения БД. Integration suite создаёт PostgreSQL container, выполняет Liquibase/SQL/TRUNCATE и создаёт/удаляет storage; обычный Gradle run также создаёт build/cache files. Выполнен статический анализ assertions и fixtures, без объявления «88 passed».

[CONFIRMED — historical artifacts] В существующем build/test-results/test найдены лишь два XML от 2026-06-22T18:33:07Z/18:33:10Z: FileItemServiceTest (2, failures0, errors0, skipped0), ChecksumSyncServiceTest (2, failures0, errors0, skipped0). Это исторические результаты 4 unit tests, не свежий прогон, не подтверждение текущей сборки и не результат integration suite. Просмотрены атрибуты suite без копирования system-out.

## Подсистемы и сценарии

| Subsystem | Tests found | Coverage by scenario | Missing critical scenarios |
| --- | --- | --- | --- |
| Startup/DB | PhotoCloudServerApplicationTests:1 | Поднятие Spring context через PostgreSQL test base | Upgrade с реальными old data, rollback, migration10 data loss, migration14 duplicates |
| Auth/errors | AuthErrorHandlingIntegrationTest:30 | DTO validation; unknown/wrong login; disabled/banned login; lastLoginAt; duplicate register; invalid/used/expired activation; reset JSON/legacy query; successful reset; unknown/revoked refresh; logout ownership/unknown/own; logout-others ownership/own; protected missing access | Happy register/email/confirm, successful/expired signed refresh, access expiry/type, ban-after-login, logout-all, refresh/logout race, reset retaining tokens |
| Profile | ProfileIntegrationTest:1 | New fields createdAt/displayName/lastLoginAt, отсутствие firstName/lastName/createAt | Stub statuses, nullable legacy values |
| Files/storage/sync | FileFlowIntegrationTest:39 | Upload metadata shape, tuple idempotency/different folders, name conflict, independent copy, rename/move, ownership, streaming/no getBytes/IO cleanup/limit, MIME detection, list/sort/DTO projection, download, checksums/exists, delete, filename safety | /files/upload alias, real parallel uploads, move checksum constraint, partial copy/move failure, cleanup failure, process crash, real large servlet multipart, large downloads, proper rich EXIF/video metadata |
| Folders | FolderApiIntegrationTest:13 | Root sequential once; protected system folders; reserved/case-insensitive names; nested user folders; cycle/self/foreign restrictions; empty-only delete; children scope/default folders | Real concurrent root/system creation, opposing moves, happy rename/move, commit-time duplicate, exhaustive foreign operations |
| DB compensation | FileItemServiceTest:2 | Real temp disk, mocked repository failure after final move; mocked race winner | Реальная БД rollback/commit/flush и race concurrency, mapper failure после commit |
| Batch service | ChecksumSyncServiceTest:2 | Lowercase/dedup/order; size limit до folder/repository calls | Полный boundary 500, изменение config, large payload performance |
| Email/SMTP/templates | Отдельных тестов нет | Reset/activation code errors в auth tests; без SMTP happy path | MailException, null cause, delivery false success, host origin, HTML page501, concurrency code reuse |
| Observability/security resource usage | Отдельных тестов нет | Basic auth failure status tests | Secret masking, binary caching memory, CORS/preflight, logging exception finalization |

[CONFIRMED] Counts: application1 + auth30 + profile1 + file39 + folder13 + service2 + sync2 =88. Наличие assert-сценария указано отдельно от его фактического исполнения.

## Test harness

[CONFIRMED] AbstractIntegrationTest: @SpringBootTest, @AutoConfigureMockMvc, @ActiveProfiles(test), @Testcontainers, @DirtiesContext(AFTER_CLASS). Static PostgreSQLContainer postgres:16-alpine, dynamic datasource; storage temp directory, stream max1024. @BeforeEach: TRUNCATE file_metadata,file_item,stored_object,folder,refresh_token,email_requests,user_roles,users RESTART IDENTITY CASCADE, затем cleanup test directory. @AfterAll удаляет test storage. Создаваемые users enabled=true — registration/activation happy path обходится helper-ом.

[CONFIRMED] MockMvc не запускает реальную мобильную сеть, proxy и большой servlet upload. MIME/stream/file operations в ряде тестов настоящие, но байты крошечные. Некоторые tests вызывают FileItemService напрямую, обходя controller/filter/multipart parser.

[CONFIRMED] FileItemServiceTest использует Mockito repositories/transaction manager и реальный FileItemMapperImpl/generated source. TransactionStatus mock не доказывает rollback semantics PostgreSQL. «raceCondition...» последовательно возвращает Optional.empty/Optional.of и бросает искусственный DataIntegrityViolationException; конкурентных потоков нет.

## Проверка качества самих тестов

[INCONSISTENCY] FileFlowIntegrationTest.ownerDeleteRemovesAllFileItemsStoredObjectAndPhysicalFile делает два identical upload в одну default папку. После idempotency оба ответа имеют один ID: тест не доказывает удаление нескольких разных ссылок владельцем, несмотря на название.

[INCONSISTENCY] metadataExtractionFailureDoesNotFailUpload загружает обычный текст с client header image/jpeg; Tika обнаруживает содержимое. Такой test может пройти по ветке non-image, не проверив catch реального image parser exception. uploadNewFileStoresMetadata... ожидает metadata=null: не доказательство полного EXIF mapping.

[CONFIRMED] tempFileIsRemovedWhenStreamingFails ловит IOException, но не assert-ит сам факт исключения. cannotRenameRootCameraOrFiles фактически проверяет ROOT/CAMERA, FILES отсутствует. DB mutation test fileItemDtoReturnsPhysicalFieldsFromStoredObject меняет SO.checksum без FileItem.checksum и подтверждает именно projection, не согласованность денормализации.

## HTTP smoke / документация

[CONFIRMED] docs/http-tests/api-smoke-tests.http содержит 26 HTTP-запросов, comment expectations и статические переменные; client.test/assert/global.set скриптов не найдено. Это ручные примеры, не автоматическая suite. Нет всех 6 Folder endpoints и 4 file операций (/upload, rename, move, copy). api-curl-examples.md — альтернативные ручные команды, не доказательство выполнения.

[INCONSISTENCY] http-tests/README.md говорит о всех endpoints и автоматическом сохранении токенов login, но .http содержит неполный набор и не содержит response scripts. README говорит только page/size, хотя code принимает folderId. Комментарии '#OK' не эквивалентны проверяемым test results.

[CONFIRMED] Полная инвентаризация test method names и источников включена в 18-code-map.md для повторного аудита. Code coverage percentage не рассчитывался: инструмент не настроен и метрики не запускались.

