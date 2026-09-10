# 01. System Overview

> Сервер как целостная система. Всё в этом документе восстановлено по коду; расхождения с документацией вынесены отдельно.

---

## 1. Назначение приложения

**[CONFIRMED]** `photo-cloud-server` — монолитное Spring Boot приложение, единственный процесс которого одновременно является:

- REST API-сервером (`/api/v1`, порт `8080`, context-path `/`);
- владельцем реляционной БД (PostgreSQL) с логической структурой файлов;
- владельцем локального файлового хранилища (`storage.root`);
- отправителем транзакционных писем (SMTP Яндекса, `MailConfig`).

Точка входа: `PhotoCloudServerApplication` (`@SpringBootApplication`, без дополнительных `@Enable*`).

**[DOCUMENTED]** `README.md` описывает проект как «серверную часть для организации собственного облачного хранилища фотографий… рассчитан на развёртывание на личном сервере».

## 2. Внешние клиенты

| Клиент | Подтверждение | Статус |
| --- | --- | --- |
| Android-приложение | **[CONFIRMED]** `RootController` — «test endpoints for Android client connectivity checks»; `docs/api-checksum-sync-contract.md` — «Для Android-клиента это pre-check перед upload» | основной целевой клиент |
| IntelliJ HTTP Client / curl / Postman | **[CONFIRMED]** `docs/http-tests/api-smoke-tests.http`, `.idea/httpRequests/` | инструмент разработки |
| Swagger UI | **[CONFIRMED]** springdoc, `/swagger-ui/**` в permitAll | инструмент разработки |
| Браузерный веб-клиент | **[CONFIRMED]** отсутствует: CORS не настроен, HTML-страницы reset пароля возвращают `501`, `src/main/resources/static/` содержит только `css/style.css` | НЕ поддерживается |
| Email-клиент пользователя | **[CONFIRMED]** ссылка подтверждения регистрации ведёт на `GET /api/v1/auth/register/confirm?code=...` и открывается браузером | частично |

## 3. Основные пользовательские сценарии

**[CONFIRMED]** по контроллерам и сервисам:

1. **Регистрация** → письмо с кодом → переход по ссылке → аккаунт `enabled`.
2. **Вход** → пара access/refresh token.
3. **Обновление access token** по refresh token.
4. **Выход**: текущее устройство / все устройства / все кроме текущего.
5. **Восстановление пароля**: запрос по email → письмо с кодом → подтверждение нового пароля.
6. **Просмотр профиля**.
7. **Работа с деревом папок**: получить root, получить прямых потомков, создать/переименовать/переместить/удалить пользовательскую папку.
8. **Загрузка файла**: с явным `folderId` или без него (тогда `Camera` для IMAGE/VIDEO, `Files` для остального).
9. **Pre-check перед загрузкой**: отправить batch SHA-256 и `folderId`, получить `existing`/`missing`.
10. **Просмотр списка файлов** (постранично, опционально по папке).
11. **Получение карточки файла** и **скачивание** содержимого.
12. **Переименование / перемещение / копирование / удаление** файла.
13. **Проверка связи** (`/test`, `/test/auth`).

## 4. Основные серверные функции

**[CONFIRMED]**

| Функция | Где реализована |
| --- | --- |
| Валидация входных данных | Bean Validation в DTO + `GlobalExceptionHandler` |
| Аутентификация каждого запроса | `JwtAuthenticationFilter` |
| Авторизация по владельцу | все repository-методы вида `findByIdAndUserId` |
| Подсчёт SHA-256 при загрузке | `FileUtils.writeAndCalculateSHA256()` |
| Определение MIME по содержимому | `FileContentDetector` (Apache Tika) |
| Классификация файла в `FileType` | `FileType.fromMimeType()` |
| Извлечение EXIF/GPS | `DrewFileMetadataExtractor` |
| Генерация физического пути и имени | `StorageKeyGenerator`, `FilenameSanitizer` |
| Защита от path traversal | `StoragePathResolver.resolve()` |
| Ленивая инициализация дерева папок | `FolderService.getOrCreateRoot()`, `getDefaultFolder()` |
| Обнаружение дубликатов | `FileItemRepository.findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc` + БД-constraint |
| Отзыв refresh token | `JwtService.revoke*` |
| Рендеринг и отправка писем | `EmailService` + Thymeleaf |
| Единый формат ошибок | `ErrorResponse` / `ErrorCode` |

## 5. Границы ответственности сервера

**[CONFIRMED] Сервер отвечает за:**

- целостность связей `User → Folder → FileItem → StoredObject → физический файл`;
- уникальность email пользователя;
- уникальность `(user, folder, checksum)` для `FileItem`;
- уникальность имени папки внутри родителя (case-insensitive);
- уникальность `originalName` внутри папки, **кроме** `CAMERA`;
- корректность `detectedMimeType`, `fileType`, `size`, `checksum` (пересчитывает сам, не доверяет клиенту);
- время `uploadedAt`;
- выпуск и отзыв токенов;
- физическое размещение байтов.

**[CONFIRMED] Сервер НЕ отвечает за:**

- состояние локальной галереи клиента, локальные ID, статусы синхронизации на устройстве;
- удаление файла на устройстве после загрузки;
- очередь загрузок, retry, дозагрузку после разрыва сети;
- разрешение конфликтов «файл изменён на устройстве»;
- идентификацию устройства (модели `Device` нет);
- миниатюры/превью;
- восстановление удалённого (корзины нет).

## 6. Authoritative-данные сервера

**[CONFIRMED]** Сервер является источником истины для:

| Данные | Обоснование по коду |
| --- | --- |
| `FileItem.id`, `Folder.id`, `User.id` | `GenerationType.IDENTITY` |
| `checksum` | считается сервером в `FileUtils.writeAndCalculateSHA256()`, клиентский checksum в upload не принимается вообще |
| `size` | считается сервером при стриминге |
| `detectedMimeType` | Tika по содержимому; заголовок multipart игнорируется |
| `fileType` | выводится из `detectedMimeType` |
| `uploadedAt` | `LocalDateTime.now()` на сервере |
| `filePath`, `filename`, `fileExtension` | генерируются сервером, наружу не отдаются |
| `folderType` | назначается сервером (`ROOT`/`CAMERA`/`FILES` — системные, `USER` — при создании через API) |
| `enabled`, `banned`, `roles`, `createdAt`, `lastUpdate`, `lastLoginAt` | назначаются/обновляются сервером |
| Факт существования дубликата в папке | БД-constraint + проверка сервиса |

## 7. Данные, поступающие от клиента

**[CONFIRMED]**

| Данные | Endpoint | Валидация |
| --- | --- | --- |
| `email`, `password` | register, login | `@Email`, `@NotBlank`, `@Size(4..20)` |
| `code` | register confirm, password reset confirm | `@NotBlank` (register confirm — без валидации, `@RequestParam String`) |
| `refreshToken` | refresh, logout, logout-others | `@NotBlank` |
| Байты файла (`file`) | upload | не пустой, лимит размера |
| `folderId` | upload, list, checksums/exists, move | опционально/`@NotNull`; проверка владения |
| `originalName` | rename, copy | `@NotBlank`, `@Size(max=255)`, sanitize |
| `name`, `parentId`, `targetParentId` | folders | `@NotBlank`, `@Size(max=255)`, `@NotNull` |
| `checksums[]` | checksums/exists | `@NotEmpty`, каждый `^[0-9a-fA-F]{64}$`, batch ≤ 500 |
| `page`, `size` | list | **[RISK]** без ограничений |

**[CONFIRMED]** Клиент **не может** задать: `capturedAt`, `checksum`, `size`, `mimeType`, `fileType`, `uploadedAt`, `deletedAt`, `id`, `folderType`, роли.

## 8. Данные, генерируемые сервером

**[CONFIRMED]**

- Все первичные ключи (`BIGINT IDENTITY`).
- `checksum` (SHA-256 hex lowercase), `size`, `detectedMimeType`, `fileType`, `fileExtension`.
- `capturedAt` — из EXIF (`DateOriginal`, иначе `DateDigitized`); при отсутствии — **fallback на `uploadedAt`** (`FileItemService.uploadFile()`).
- `filePath` (шардинг по checksum), `filename` (`originalName_uuid.ext`).
- `EmailRequest.code` — `UUID.randomUUID()`.
- JWT access/refresh, `jti` refresh-токена.
- `ErrorResponse.id` — `UUID.randomUUID()` на каждую ошибку.
- Timestamps: `createdAt`/`updatedAt` (`@CreationTimestamp`/`@UpdateTimestamp`), `uploadedAt`, `revokedAt`.
- Ссылки в письмах (собираются из `HttpServletRequest` в `EmailService.getEmailContext()`).

## 9. Постоянно хранимые данные

**[CONFIRMED]**

| Хранилище | Что лежит |
| --- | --- |
| PostgreSQL | пользователи, роли, refresh-токены, email-коды, папки, логические файлы, физические объекты, метаданные |
| Файловая система `storage.root` | байты файлов |
| Файловая система `storage.temp-dir` | временные файлы **только на время загрузки** (удаляются в любом исходе) |

## 10. Временно вычисляемые данные

**[CONFIRMED]**

- Полный абсолютный путь файла: собирается в `StoragePathResolver.resolve(filePath, filename)` и в БД не хранится (соответствует пункту 18 в `TODO.txt`).
- `PageResponse` (агрегаты пагинации) — из `Page` во время запроса.
- `ChecksumExistsResponse.existing/missing` — вычисляются на лету, нигде не сохраняются.
- Access token — не хранится нигде на сервере.
- `EmailRequest.isActive()`/`isExpired()` — вычисляются по `createdAt + 3 дня`, не колонка.
- `User.getAuthorities()` — из `roles`.
- Content-Disposition/Content-Type ответа download — из `FileItem.originalName` и `StoredObject.detectedMimeType`.

## 11. Текстовая схема потока

```
                    ┌──────────────────────────────────────────────────┐
   Android / HTTP   │                    CLIENT                        │
   client           │  Authorization: Bearer <accessToken>             │
                    └──────────────────────┬───────────────────────────┘
                                           │ HTTP/JSON, multipart/form-data
                                           v
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ FILTER CHAIN                                                                │
 │  HttpLoggingFilter (Ordered.HIGHEST_PRECEDENCE, логирует request/response)   │
 │  JwtAuthenticationFilter  -> JwtService.extractTokenType/extractUserName     │
 │                           -> UserService.loadUserByUsername                  │
 │                           -> SecurityContextHolder                           │
 │  SecurityConfig: permitAll(...) | anyRequest().authenticated()               │
 │  JsonAuthenticationEntryPoint (401) / JsonAccessDeniedHandler (403)          │
 └────────────────────────────────────┬────────────────────────────────────────┘
                                      v
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ CONTROLLER LAYER (@RestController, /api/v1)                                  │
 │  RootController · RegisterController · AuthController · PasswordController   │
 │  UserController · FolderController · FileController                          │
 │  @AuthenticationPrincipal User  ·  @Valid DTO  ·  ResponseEntity<Dto>        │
 └────────────────────────────────────┬────────────────────────────────────────┘
                                      v
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ SERVICE LAYER                                                                │
 │  UserService ── JwtService ── EmailRequestService ── EmailService             │
 │  FolderService ◄── FileItemService ──► ChecksumSyncService                    │
 │  helpers: StorageKeyGenerator · StoragePathResolver · FilenameSanitizer       │
 │           FileContentDetector (Tika) · DrewFileMetadataExtractor (EXIF)       │
 │           FileUtils.writeAndCalculateSHA256                                   │
 └───────────────┬─────────────────────────────────────┬───────────────────────┘
                 │                                     │
                 v                                     v
 ┌───────────────────────────────┐      ┌──────────────────────────────────────┐
 │ PERSISTENCE (Spring Data JPA) │      │ FILE STORAGE (java.nio.file)         │
 │  UserRepository               │      │  <storage.temp-dir>/upload-*.tmp     │
 │  RefreshTokenRepository       │      │  <storage.root>/users/{id}/objects/  │
 │  EmailRequestRepository       │      │        {cs0:2}/{cs2:4}/{name}_{uuid} │
 │  FolderRepository             │      │  ATOMIC_MOVE temp -> final           │
 │  FileItemRepository           │      │                                      │
 │  StoredObjectRepository       │      │                                      │
 │  FileMetadataRepository[UNUSED]│     │                                      │
 └───────────────┬───────────────┘      └──────────────────────────────────────┘
                 v                                     
 ┌───────────────────────────────┐      ┌──────────────────────────────────────┐
 │ PostgreSQL (Liquibase)        │      │ SMTP smtp.yandex.ru:465 (JavaMail)   │
 │ users, user_roles,            │      │ Thymeleaf email templates            │
 │ refresh_token, email_requests,│      └──────────────────────────────────────┘
 │ folder, stored_object,        │
 │ file_item, file_metadata      │
 └───────────────────────────────┘
```

## 12. Что отсутствует в системе целиком

**[CONFIRMED]** — проверено grep'ом по `src/main`:

- фоновые задачи (`@Scheduled`, `@EnableScheduling`) — нет;
- асинхронная обработка (`@Async`, `@EnableAsync`, `Executor`) — нет;
- очереди/брокеры — нет;
- кэш (`@Cacheable`) — нет;
- health/metrics/tracing (Actuator, Micrometer) — нет;
- WebSocket/SSE — нет;
- rate limiting — нет;
- multi-tenancy, sharing, ACL — нет;
- аудит-лог действий — нет.
