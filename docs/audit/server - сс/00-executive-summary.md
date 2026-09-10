# 00. Executive Summary

> Аудит As-Is серверной части `photo-cloud-server`.
> Дата анализа: 2026-09-10. Ветка: `master`, HEAD `c2e9593 cleanup`.
> Метки: **[CONFIRMED]** код · **[DOCUMENTED]** только документация · **[INFERRED]** следует из структуры · **[PARTIAL]** реализовано частично · **[UNUSED]** не используется · **[INCONSISTENCY]** расхождение · **[RISK]** потенциальная проблема.

---

## 1. Назначение серверной части

**[CONFIRMED]** `photo-cloud-server` — backend личного облачного файлового хранилища. Он предоставляет REST API (`/api/v1`) для:

- регистрации/подтверждения пользователя и восстановления пароля по email;
- аутентификации по JWT (access + refresh);
- логической файловой структуры (дерево папок в БД);
- загрузки, скачивания, переименования, перемещения, копирования и удаления файлов;
- batch-проверки SHA-256 checksum перед загрузкой (pre-check для мобильного клиента).

Основной подразумеваемый клиент — Android-приложение: `RootController` явно комментирует «lightweight test endpoints for Android client connectivity checks» ([RootController.java:17](../../../src/main/java/ru/tbcarus/photocloudserver/controller/RootController.java)), а `ChecksumSyncService` описан как pre-check для Android (`docs/api-checksum-sync-contract.md`).

## 2. Технологический стек

**[CONFIRMED]** `build.gradle`:

| Слой | Технология |
| --- | --- |
| Язык/JVM | Java 17 (toolchain) |
| Framework | Spring Boot 3.4.4 (Web MVC, Data JPA, Security, Mail, Validation, Thymeleaf) |
| БД | PostgreSQL (драйвер 42.7.8), Hibernate/JPA, `ddl-auto: validate` |
| Миграции | Liquibase (`db/changelog/db.changelog-master.yml`, 14 SQL-changelog) |
| Аутентификация | JJWT 0.12.6 (HMAC-SHA), BCrypt |
| API-docs | springdoc-openapi 2.8.3 (Swagger UI) |
| Маппинг | MapStruct 1.6.3, Lombok |
| Медиа | metadata-extractor 2.19.0 (EXIF), Apache Tika 3.1.0 (MIME по содержимому) |
| Тесты | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (postgres:16-alpine), Mockito, AssertJ |
| Сборка | Gradle 8.13 (wrapper) |

**[CONFIRMED]** Отсутствуют: Spring Boot Actuator, кэш, очереди сообщений, Redis, S3/облачное хранилище, Docker-файлы, CI-конфигурация (каталога `.github` нет).

## 3. Основные подсистемы

| Подсистема | Ключевые классы | Статус |
| --- | --- | --- |
| Auth/JWT | `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`, `UserService` | IMPLEMENTED |
| Регистрация/email | `RegisterController`, `EmailRequestService`, `EmailService` | PARTIAL (нет resend, нет rate-limit) |
| Профиль | `UserController` | PARTIAL (только GET, 3 заглушки 501) |
| Папки | `FolderController`, `FolderService`, `Folder` | IMPLEMENTED (без recursive delete) |
| Файлы | `FileController`, `FileItemService`, `FileItem`, `StoredObject`, `FileMetadata` | IMPLEMENTED |
| Физическое хранилище | `StoragePathResolver`, `StorageKeyGenerator`, `FilenameSanitizer`, `StorageProperties` | IMPLEMENTED |
| Метаданные | `DrewFileMetadataExtractor`, `FileContentDetector` | PARTIAL (только изображения) |
| Checksum sync | `ChecksumSyncService`, `ChecksumExistsProperties` | PARTIAL (только pre-check) |
| Ошибки | `GlobalExceptionHandler`, `ErrorResponse`, `ErrorCode` | IMPLEMENTED |
| Фоновые задачи | — | NOT FOUND |
| Метрики/health | — | NOT FOUND |

## 4. Хранение метаданных

**[CONFIRMED]** PostgreSQL, 8 таблиц: `users`, `user_roles`, `refresh_token`, `email_requests`, `folder`, `stored_object`, `file_item`, `file_metadata`.

Ключевое архитектурное решение — **разделение логического и физического слоя**:

- `FileItem` — логическая запись файла в дереве пользователя (`originalName`, `folder`, `capturedAt`, `uploadedAt`, `checksum`);
- `StoredObject` — физический объект на диске (`filePath`, `filename`, `fileExtension`, `checksum`, `size`, `detectedMimeType`, `fileType`).

Поэтому `move`/`rename` меняют только строку в БД и не трогают диск (`FileItemService.moveFileForCurrentUser()`, `FileItemService.renameFileForCurrentUser()`).

**[CONFIRMED]** Дубликат определяется как **`user + folder + checksum`** — это ограничение БД `uk_file_item_user_folder_checksum` (migration `14-file-item-user-folder-checksum.sql`) плюс проверка в `FileItemService.uploadFile()`.

## 5. Хранение файлов

**[CONFIRMED]** Локальная файловая система, работающая как object storage (не зеркало дерева папок).

Схема пути (`StorageKeyGenerator.generateFilePath()` + `StorageKeyGenerator.generateFilename()`):

```
<storage.root>/users/{userId}/objects/{checksum[0:2]}/{checksum[2:4]}/{originalName}_{uuid}.{ext}
```

Подтверждено реальным содержимым каталога `storage/` в рабочей копии, например
`storage/users/3/objects/09/02/1. Черный кот с рыбкой.jpg_538a8a04-8f80-42b1-b1ad-5b496fd0ee68.jpg`.

Upload: stream → temp-файл (SHA-256 и размер считаются на лету) → определение MIME по содержимому (Tika) → EXIF → `Files.move(..., ATOMIC_MOVE)` в final → транзакция в БД. Файловая операция выполняется **до** записи в БД, и при падении БД final-файл удаляется ([FileItemService.java:117-160](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java)).

## 6. Модель аутентификации

**[CONFIRMED]**

- **Access token** — JWT, `token_type=ACCESS`, `subject=email`, claim `roles`, TTL **20 минут** (`JwtService.expirationTime`), в БД не хранится, отозвать нельзя.
- **Refresh token** — JWT, `token_type=REFRESH`, с `jti`, TTL **7 дней** (`JwtService.refreshExpirationTime`), хранится в таблице `refresh_token`, отзывается флагом `revoked`.
- Заголовок: `Authorization: Bearer <accessToken>`.
- Сессий на сервере нет: `SessionCreationPolicy.STATELESS`, CSRF выключен.
- `POST /auth/refresh-token` выдаёт **только новый access token**; refresh token не ротируется.
- Пароли — BCrypt (`EncoderConfig`).

## 7. Основные функции

**[CONFIRMED]** 36 HTTP-маппингов в 7 контроллерах (полная инвентаризация — `05-api.md`). Реализованы: register/confirm, login, refresh, logout (3 варианта), password reset request/confirm, profile GET, дерево папок (root/children/create/rename/move/delete), файлы (upload ×2, list, get, download, rename, move, copy, delete, checksums, checksums/exists), 2 test-endpoint'а. Возвращают `501 Not Implemented`: 7 зарезервированных endpoint'ов.

## 8. Примерный уровень готовности

**[INFERRED]** — оценка на основании соотношения реализованных и заглушенных функций, покрытия тестами и списка TODO в коде:

| Область | Готовность | Комментарий |
| --- | --- | --- |
| Auth/JWT | ~85% | Работает; нет ротации refresh, нет revoke при смене пароля |
| Регистрация/email | ~60% | Resend не реализован, rate-limit написан, но не подключён |
| Профиль | ~25% | Только чтение |
| Папки | ~80% | Нет recursive delete |
| Файлы | ~85% | Нет thumbnails, soft delete, sharing, batch upload |
| Sync-протокол | ~40% | Только pre-check по одной папке; нет link-existing, device model, sync sessions |
| Наблюдаемость/эксплуатация | ~15% | Нет health, метрик, tracing, CI, Docker |

**Общая оценка: рабочий прототип / ранний MVP**, пригодный для одного пользователя-владельца сервера, но не подготовленный к многопользовательской эксплуатации.

## 9. Наиболее важные архитектурные особенности

1. **[CONFIRMED]** Разделение `FileItem` (логика) ↔ `StoredObject` (физика): логические операции не трогают диск.
2. **[CONFIRMED]** Дедупликация ограничена **папкой**: одинаковые байты в двух разных папках = 2 `StoredObject` + 2 физических файла. Это осознанное решение, зафиксированное в комментариях кода и в `docs/application-overview.md` §10.
3. **[CONFIRMED]** Upload идемпотентен в пределах папки: повторная загрузка тех же байтов возвращает существующий `FileItem` с HTTP 200.
4. **[CONFIRMED]** Системные папки `ROOT`/`Camera`/`Files` создаются лениво и race-safe (через перехват `DataIntegrityViolationException` + повторное чтение).
5. **[CONFIRMED]** Файл никогда не читается целиком в память: streaming в temp-файл с параллельным подсчётом SHA-256 (`FileUtils.writeAndCalculateSHA256()`), проверено тестом `uploadDoesNotCallMultipartFileGetBytes`.
6. **[CONFIRMED]** Доступ к чужим объектам не даёт `403`: все repository-запросы идут по `id + userId`, поэтому клиент получает `404`.
7. **[CONFIRMED]** MIME определяется по содержимому (Tika), а не по заголовку multipart (тест `mimeTypeIsDetectedFromContentInsteadOfMultipartHeader`).

## 10. Наиболее заметные незавершённые части

**[CONFIRMED]**

- 7 endpoint'ов-заглушек `501` (profile update, profile settings ×2, register resend, password reset resend, password reset page ×2).
- `FileItem.deletedAt` существует в схеме и DTO, но **никогда не заполняется** — soft delete/корзины нет, удаление hard.
- `Role.ADMIN` объявлена, но нигде не назначается и не проверяется; `@EnableMethodSecurity` включён, но ни одной `@PreAuthorize`/`@Secured` в коде нет.
- `EmailRequestService.checkAndGenerateCode()` реализует лимит «3 запроса за 3 дня», но **не вызывается** ни из одного места; `EmailRequestService.delete()` — пустой метод.
- Sharing/права доступа: в `FileItemService.deleteFileForCurrentUser()` есть ветка «текущий пользователь не владелец `StoredObject`», но при текущей модели данных она недостижима в production.
- Нет thumbnails, альбомов, тегов, поиска, версионирования, batch-upload, link-existing, device-модели.
- Извлечение метаданных работает только для изображений: `DrewFileMetadataExtractor.extract()` сразу возвращает пустой объект для не-`image/*`, поэтому `durationSec` для видео всегда `null`.

## 11. Основные обнаруженные риски

Полный список — в `16-known-gaps-and-risks.md`. Наиболее существенные:

| # | Риск | Severity |
| --- | --- | --- |
| 1 | **[CONFIRMED][RISK]** `HttpLoggingFilter` логирует тела всех запросов/ответов на уровне INFO, включая пароль в `POST /auth/login` и оба JWT в ответе | HIGH |
| 2 | **[CONFIRMED][RISK]** `POST /files/{id}/move` не проверяет `checksum` в целевой папке → нарушение `uk_file_item_user_folder_checksum` → `500 DATABASE_CONSTRAINT_VIOLATION` вместо `409` | HIGH |
| 3 | **[CONFIRMED][RISK]** `UserService.loadUserByUsername()` вызывает `Optional.get()` без проверки → `NoSuchElementException` → `500` при валидном JWT удалённого пользователя | HIGH |
| 4 | **[CONFIRMED][RISK]** Смена пароля не отзывает refresh token'ы: украденный refresh продолжает работать после reset | HIGH |
| 5 | **[CONFIRMED][RISK]** Удаление пользователя каскадно удаляет строки БД, но **физические файлы на диске остаются навсегда** (orphan files); процедуры cleanup нет | HIGH |
| 6 | **[CONFIRMED][RISK]** `refresh_token.token` — `TEXT` без индекса и без unique; `findByToken` выполняется на каждом refresh/logout → seq scan | MEDIUM |
| 7 | **[CONFIRMED][RISK]** `refresh_token` не имеет FK на `users` (связь по строке `user_name` = email) → токены не удаляются при удалении пользователя | MEDIUM |
| 8 | **[CONFIRMED][RISK]** `rename`/`move`/`copy` не помечены `@Transactional`, а уникальность `originalName` не защищена ограничением БД → гонка создаёт дубликаты имён | MEDIUM |
| 9 | **[CONFIRMED][RISK]** User enumeration: `POST /auth/register` даёт `409` для существующего email, `POST /auth/password/reset/request` даёт `400` для несуществующего | MEDIUM |
| 10 | **[CONFIRMED][RISK]** `GET /files` и `GET /files/checksums` не ограничивают `size` / объём выдачи | MEDIUM |
| 11 | **[CONFIRMED][RISK]** `UserService.register()` при `MessagingException` вызывает `e.getCause().getMessage()` → NPE, если cause `null` → `500` после создания пользователя | MEDIUM |
| 12 | **[CONFIRMED][RISK]** CORS не сконфигурирован вообще → браузерный клиент невозможен без изменения кода | MEDIUM |
| 13 | **[CONFIRMED][RISK]** `spring.jpa.show-sql: true` в основном `application.yml` | LOW |
| 14 | **[CONFIRMED][INCONSISTENCY]** Письмо восстановления пароля ведёт на `/auth/password/reset/page`, который возвращает `501` | MEDIUM |
| 15 | **[CONFIRMED][INCONSISTENCY]** HTML-шаблоны писем содержат брендинг чужого проекта («Spending web App», `spending-sb@yandex.ru`) | LOW |

## 12. Что читать дальше

- Архитектура и слои — `02-architecture.md`
- Модель данных и ER — `03-data-model.md`, физическая схема — `04-database.md`
- Полная инвентаризация API — `05-api.md`
- Security-flow — `06-auth-security.md`
- Хранилище — `07-file-storage.md`
- Сквозные процессы и sequence-диаграммы — `08-business-processes.md`
- Пакет для аудита Android-клиента — `client-handoff/`
