# Тестирование: текущее состояние

Этот раздел сводит два исходных аудита, а не выполняет новый тест-аудит. Наличие сценария в исходниках не означает подтверждённый успешный прогон. При консолидации тесты и сервер не запускались, БД/SMTP не подключались.

## Инфраструктура

JUnit5, Mockito, AssertJ, Spring Boot Test/Security Test, MockMvc и Testcontainers PostgreSQL. Integration base включает test profile, PostgreSQL16-alpine, Liquibase на чистой схеме, temporary filesystem и service limit1024 bytes. BeforeEach очищает восемь таблиц и тестовое storage. MockMvc проверяет MVC flow в JVM, не реальный TCP/TLS и не все servlet upload limits.

FileItemServiceTest использует mock repositories/transaction manager, но реальное IO/Tika/path helpers; ChecksumSyncServiceTest — mocks и нормализацию. Mocked violation не доказывает recovery реальных конкурентных PostgreSQL transactions.

## Сведённое покрытие

| Подсистема | Существующие сценарии | Существенные непокрытые области по аудитам |
| --- | --- | --- |
| Bootstrap/schema | Context load, чистая schema через Liquibase | Upgrade непустой legacy DB, конфликтные данные migrations11/12/14 |
| Auth | Validation, login unknown/wrong/disabled/banned, lastLoginAt, duplicate register, invalid/used/expired activation, reset success/errors, invalid/revoked refresh, own/foreign/unknown logout и logout-others, invalid/missing access | Successful refresh и реальные expired JWT, logout-all, invalid Bearer public, ban после выдачи, concurrent revoke/refresh, полноценная SMTP доставка |
| Profile | Поля UserDto и актуальные имена полей | Заглушки, полный lifecycle и legacy null fields |
| Files | Upload, same-folder duplicate, cross-folder copies, MIME, names, size/temp failure, list/sort/folder filter, rename/move/copy/delete, bytes download, foreign/missing IDs, missing physical, DTO physical fields | Explicit upload alias, real multipart limit110MB, large/Range download, move checksum500, concurrent writes, partial IO и crash recovery |
| Folders | Sequential lazy root, ownership, reserved/sibling names, system restrictions, self/descendant move rejection, empty delete, children | Successful rename/move, concurrent root/cycle/empty-delete checks, deep trees |
| Checksum | Validation, ownership/scope, >max, lowercase/dedup/order, existing/missing combinations | Нагрузочные свойства, physical divergence repair |
| Upload compensation | Mock DB failure cleanup, simulated duplicate race | Реальная concurrency, mapper-after-commit, cleanup failure |
| Metadata | Nullable result и capturedAt fallback | Богатый EXIF/GPS roundtrip, video metadata; exception branch не доказан одним именем теста |

## Ограничения свидетельств

Owner-delete test делает два upload одного содержимого в default folder. По текущей dedup semantics это одна logical identity, поэтому две проверки ID не доказывают удаление нескольких references. Сам production deleteAll подтверждён отдельно. Non-owner test вручную создаёт cross-owner reference; это не sharing endpoint.

`metadataExtractionFailureDoesNotFailUpload` подаёт текстовые bytes с image multipart header; Tika определяет тип по содержимому, поэтому тест не гарантирует вход в exception branch image extractor. Fallback подтверждается production catch. Точечная проверка этих случаев отражена в VER-SRV-020.

A указывает приблизительно70 методов, B —88 declarations. Это различие инвентаризации, не архитектурный конфликт и не доказательство изменения production-версии; повторный подсчёт не нужен для спецификации. В документах нет утверждения о числе пройденных тестов. Точечные ограничения assertions из B сохранены как ограничения доказательности, а не как отдельные требования.

## Ручные проверки и tooling

HTTP smoke-файл покрывает часть auth/profile/files. По обоим аудитам он не охватывает все folder routes, explicit upload и file rename/move/copy. Curl examples обрываются без команд. Test logging маскирует tokens/Authorization; это не полная маскировка passwords/codes и не production logger.

Настроенных JaCoCo coverage, нагрузочных/контрактных тестов и CI workflow в поставляемом проекте нет. Проценты покрытия и оценка «готовности» не выводятся из числа тестов.
