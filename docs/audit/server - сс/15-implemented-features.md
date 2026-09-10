# 15. Implemented Features — Master Matrix

> Статусы: **IMPLEMENTED** — работает end-to-end · **PARTIAL** — работает не полностью · **STUB** — есть точка входа, реализации нет · **UNUSED** — код существует, но не вызывается · **NOT FOUND** — в проекте отсутствует.
> В матрицу включены только те возможности, следы которых есть в коде, схеме БД или документации проекта. Идеи развития сюда не включены.

---

## 1. Аутентификация и управление аккаунтом

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Регистрация пользователя | IMPLEMENTED | `RegisterController.register`, `UserService.register`, `UserRegisterMapper` | `201`; email → lowercase; `enabled=false`; роль `USER` |
| Подтверждение email по коду | IMPLEMENTED | `RegisterController.verifyEmail`, `EmailRequestService.confirmRegistration` | код живёт 3 дня; ответ `text/plain` |
| Повторная отправка кода активации | STUB | `RegisterController.resendVerifyEmail` | `501`; TODO в коде |
| Ограничение частоты email-запросов (3 за 3 дня) | UNUSED | `EmailRequestService.checkAndGenerateCode` | метод написан, **не вызывается** ни из одного места |
| Login (email + пароль) | IMPLEMENTED | `AuthController.login`, `UserService.login` | обновляет `lastLoginAt`; все отказы → `401 INVALID_CREDENTIALS` |
| Выдача access token | IMPLEMENTED | `JwtService.generateAccessToken` | TTL 20 мин (хардкод) |
| Выдача refresh token | IMPLEMENTED | `JwtService.generateRefreshToken` | TTL 7 дней (хардкод); строка в `refresh_token` |
| Обновление access token | IMPLEMENTED | `AuthController.refresh`, `JwtService.refreshAccessToken` | refresh **не ротируется**; `enabled/banned` не проверяются |
| Logout текущей сессии | IMPLEMENTED | `AuthController.logout`, `JwtService.revokeOwnedToken` | ставит `revoked` + `revokedAt` |
| Logout на всех устройствах | IMPLEMENTED | `AuthController.logoutAll`, `JwtService.revokeAll` | **`revokedAt` не заполняется** |
| Logout на всех, кроме текущего | IMPLEMENTED | `AuthController.logoutOthers`, `JwtService.revokeOtherOwnedToken` | то же |
| Отзыв access token | NOT FOUND | — | принципиально невозможно: не хранится |
| Ротация refresh token | NOT FOUND | — | закомментированная заготовка в `JwtService` |
| Запрос сброса пароля | IMPLEMENTED | `PasswordController.forgotPassword`, `UserService.forgotPassword` | нет rate-limit; `400` для неизвестного email |
| Подтверждение сброса пароля | IMPLEMENTED | `PasswordController.resetPassword`, `EmailRequestService.resetPassword` | инвалидирует все активные `PASSWORD_RESET`-коды |
| Отзыв токенов при смене пароля | NOT FOUND | — | зафиксировано и в `docs/api-user-contract.md` |
| Повторная отправка ссылки сброса | STUB | `PasswordController.resendResetPassword` | `501` |
| HTML-страница сброса пароля (GET) | STUB | `PasswordController.getResetPasswordPage` | `501`; **письмо ведёт именно сюда** |
| HTML-страница сброса пароля (POST) | STUB | `PasswordController.submitResetPasswordPage` | `501` |
| Хеширование паролей | IMPLEMENTED | `EncoderConfig` (BCrypt, strength 10) | требования к сложности отсутствуют |
| Роли пользователей | PARTIAL | `Role`, `User.roles`, `user_roles` | роли хранятся и попадают в JWT, но **ни одной проверки прав в коде нет** |
| Роль ADMIN | UNUSED | `Role.ADMIN` | никому не назначается |
| Method security (`@PreAuthorize`) | UNUSED | `@EnableMethodSecurity` в `SecurityConfig` | включено, но не используется |
| Блокировка пользователя (ban) | PARTIAL | `User.banned`, `isAccountNonLocked()` | флаг учитывается при login, но **механизма его установки нет** |
| Удаление пользователя | NOT FOUND | — | `TODO.txt` п.13 |
| Смена email | NOT FOUND | — | email — логин, менять нельзя (`README-description.md`) |
| Смена пароля из профиля (со старым паролем) | NOT FOUND | — | только через email-flow |
| MFA / 2FA | NOT FOUND | — | |
| OAuth / внешние провайдеры | NOT FOUND | — | |
| Rate limiting / защита от brute force | NOT FOUND | — | `TODO.txt` п.2.5 |

---

## 2. Профиль пользователя

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Просмотр профиля | IMPLEMENTED | `UserController.getProfile` | DTO собирается из principal, без обращения к БД |
| Редактирование профиля | STUB | `UserController.updateProfile` | `501` |
| Просмотр настроек | STUB | `UserController.getSettings` | `501`; модели настроек нет |
| Изменение настроек | STUB | `UserController.updateSettings` | `501` |
| Аватар пользователя | NOT FOUND | — | |
| Квоты / статистика по месту | NOT FOUND | — | |

---

## 3. Дерево папок

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Получение ROOT (с ленивым созданием) | IMPLEMENTED | `FolderController.getRoot`, `FolderService.getOrCreateRoot` | race-safe через unique-индекс |
| Системные папки `Camera` / `Files` | IMPLEMENTED | `FolderService.getDefaultFolder`, `getOrCreateSystemChild` | создаются лениво при первой загрузке |
| Получение прямых потомков | IMPLEMENTED | `FolderController.getChildren` | без рекурсии; сортировка `lower(name), id` |
| Создание пользовательской папки | IMPLEMENTED | `FolderController.createFolder` | `200` (не `201`) |
| Переименование папки | IMPLEMENTED | `FolderController.renameFolder` | только `USER` |
| Перемещение папки | IMPLEMENTED | `FolderController.moveFolder` | защита от цикла (`ensureNotDescendant`) |
| Удаление пустой папки | IMPLEMENTED | `FolderController.deleteFolder` | только `USER` и только пустой |
| Рекурсивное удаление папки | NOT FOUND | — | подробный TODO в `FolderService.deleteFolder()` |
| Защита системных папок | IMPLEMENTED | `ensureUserFolder`, `isSystemLeaf`, `ensureSystemRootNameIsNotReserved` | |
| Уникальность имени в родителе | IMPLEMENTED | `ensureNameIsFree` + БД `uk_folder_user_parent_name` | case-insensitive |
| Полное дерево одним запросом | NOT FOUND | — | клиент обходит уровень за уровнем |
| Путь до папки (breadcrumbs) | NOT FOUND | — | `FolderDto` содержит только `parentId` |
| Счётчик файлов в папке | NOT FOUND | — | |

---

## 4. Файлы

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Загрузка одного файла | IMPLEMENTED | `FileController.uploadFile`, `FileItemService.uploadFile` | streaming, без чтения в память |
| Дублирующий upload-endpoint | IMPLEMENTED | `FileController.uploadFileExplicit` | `POST /files/upload`, тот же pipeline |
| Загрузка в конкретную папку | IMPLEMENTED | параметр `folderId` | |
| Автовыбор папки по типу | IMPLEMENTED | `FolderService.getDefaultFolder` | IMAGE/VIDEO → `Camera`, остальное → `Files` |
| Загрузка нескольких файлов за раз | NOT FOUND | — | упомянуто в `TODO.txt` |
| Chunked / resumable upload | NOT FOUND | — | |
| Подсчёт SHA-256 при загрузке | IMPLEMENTED | `FileUtils.writeAndCalculateSHA256` | совмещён со стримингом |
| Ограничение размера файла | IMPLEMENTED | `storage.max-file-size-bytes` + multipart-лимиты | два барьера |
| Ограничение типов файлов | NOT FOUND | — | `TODO.txt` упоминает; whitelist отсутствует |
| Определение MIME по содержимому | IMPLEMENTED | `FileContentDetector` (Tika) | заголовок клиента игнорируется |
| Классификация в `FileType` | IMPLEMENTED | `FileType.fromMimeType` | 6 категорий |
| Извлечение EXIF (изображения) | IMPLEMENTED | `DrewFileMetadataExtractor` | размеры, камера, объектив, выдержка, ISO, GPS |
| Извлечение метаданных видео | NOT FOUND | — | `durationSec` всегда `null` |
| `capturedAt` из EXIF с fallback | IMPLEMENTED | `FileItemService.uploadFile` | fallback = `uploadedAt` |
| Список файлов с пагинацией | IMPLEMENTED | `FileController.getUserFiles`, `PageResponse` | без верхней границы `size` |
| Фильтр списка по папке | IMPLEMENTED | параметр `folderId` | только прямые файлы |
| Фильтр по типу / датам | NOT FOUND | — | упомянут в `docs/http-tests/README.md` как будущий |
| Сортировка на выбор клиента | NOT FOUND | — | сортировка фиксирована в контроллере |
| Карточка файла | IMPLEMENTED | `FileController.getFile` | физические поля не раскрываются |
| Скачивание файла | IMPLEMENTED | `FileController.downloadFile` | без Range, ETag, кэширования |
| Переименование файла | IMPLEMENTED | `FileController.renameFile` | меняет только `originalName` |
| Перемещение файла | IMPLEMENTED | `FileController.moveFile` | **не проверяет checksum → `500`** |
| Копирование файла | IMPLEMENTED | `FileController.copyFile` | создаёт независимую физическую копию |
| Удаление файла (hard) | IMPLEMENTED | `FileController.deleteFile` | `204`; удаляет `StoredObject` и файл |
| Soft delete / корзина | NOT FOUND | — | колонка `deleted_at` и поле DTO есть, механизма нет |
| Восстановление удалённого | NOT FOUND | — | |
| Замена содержимого (overwrite/replace) | NOT FOUND | — | TODO в `ensureFileNameAvailable` |
| Auto-rename при конфликте | NOT FOUND | — | TODO там же |
| Версионирование | NOT FOUND | — | |
| Миниатюры / превью | NOT FOUND | — | колонка `thumbnail_path` была в удалённой `media_file` |
| Поиск по файлам | NOT FOUND | — | |
| Теги | NOT FOUND | — | |
| Альбомы | NOT FOUND | — | |
| Расшаривание по ссылке | NOT FOUND | — | заготовка — ветка «не владелец» в delete |

---

## 5. Дедупликация и синхронизация

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Определение дубля `user+folder+checksum` | IMPLEMENTED | `FileItemRepository`, `uk_file_item_user_folder_checksum` | |
| Идемпотентный upload в пределах папки | IMPLEMENTED | `FileItemService.uploadFile` | возвращает существующий `FileItem` |
| Обработка гонки при параллельном upload | IMPLEMENTED | catch `DataIntegrityViolationException` | покрыто unit-тестом |
| Batch pre-check checksum | IMPLEMENTED | `ChecksumSyncService.checkExisting` | `POST /files/checksums/exists` |
| Нормализация и дедупликация входных checksum | IMPLEMENTED | `LinkedHashSet` + lowercase | порядок ответа сохраняется |
| Ограничение размера batch | IMPLEMENTED | `sync.checksum-exists.max-batch-size` = 500 | |
| Полный список checksum пользователя | IMPLEMENTED | `GET /files/checksums` | без пагинации; помечен как «старый» |
| Дедупликация между папками | NOT FOUND | **by design** | одинаковые байты в N папках = N копий |
| Link-existing (создать `FileItem` без передачи байтов) | NOT FOUND | — | TODO в `ChecksumSyncService`; заготовка `StoredObjectRepository.findFirstByUserIdAndChecksum...` [UNUSED] |
| Модель устройства (`Device`) | NOT FOUND | — | TODO в `api-checksum-sync-contract.md` |
| Сессии синхронизации | NOT FOUND | — | там же |
| Расширенный sync-ответ (`fileType`, `size`, `capturedAt`) | NOT FOUND | — | там же |
| Delta-sync / курсор изменений | NOT FOUND | — | |
| Уведомление клиента об изменениях (push, WebSocket) | NOT FOUND | — | |

---

## 6. Хранилище

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Локальное object storage | IMPLEMENTED | `StoragePathResolver`, `StorageKeyGenerator` | |
| Шардинг по checksum | IMPLEMENTED | `generateFilePath` | 2+2 hex-символа |
| Разделение по пользователям | IMPLEMENTED | `users/{userId}/objects/...` | |
| Санитизация имён | IMPLEMENTED | `FilenameSanitizer` | Windows-запрещённые символы, control-символы, `.`/`..` |
| Ограничение длины имени | IMPLEMENTED | 80 (префикс) / 255 (компонент) | |
| Защита от path traversal | IMPLEMENTED | `StoragePathResolver.resolve` | |
| Атомарная запись | PARTIAL | `Files.move(ATOMIC_MOVE)` + fallback | не атомарна между разными ФС |
| Временные файлы и их очистка | IMPLEMENTED | `createTempFile` + cleanup во всех ветках | не покрывает аварийное завершение процесса |
| Откат физического файла при сбое БД | IMPLEMENTED | catch-блоки в `uploadFile`/`copyFile` | покрыто тестами |
| Удаление файла с диска | PARTIAL | `Files.deleteIfExists` | сбой только логируется → orphan |
| Обнаружение orphan-файлов | NOT FOUND | — | |
| Очистка при удалении пользователя | NOT FOUND | — | файлы остаются навсегда |
| Проверка целостности (сверка checksum) | NOT FOUND | — | |
| Квоты на пользователя | NOT FOUND | — | |
| Внешние хранилища (S3 и т.п.) | NOT FOUND | — | абстракции хранилища нет |
| Шифрование файлов на диске | NOT FOUND | — | |

---

## 7. Email

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Отправка письма активации | IMPLEMENTED | `EmailService`, `templates/email/confirmationTemplate.html` | синхронно в HTTP-запросе |
| Отправка письма сброса пароля | IMPLEMENTED | `EmailService`, `passwordResetTemplate.html` | **ссылка ведёт на `501`-endpoint** |
| HTML-шаблоны | PARTIAL | Thymeleaf | содержат брендинг чужого проекта («Spending web App») |
| Генерация ссылки из контекста запроса | IMPLEMENTED | `EmailService.getEmailContext` | зависит от `HttpServletRequest`; за reverse-proxy может дать неверный хост |
| Асинхронная отправка | NOT FOUND | — | |
| Повтор при сбое | NOT FOUND | — | одна попытка, ошибка поглощается |
| Прочие уведомления | NOT FOUND | — | |
| Вложения | NOT FOUND | — | поле `EmailContext.attachment` есть, не используется |

---

## 8. Обработка ошибок и контракт

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Единый формат ошибок | PARTIAL | `ErrorResponse`, `ErrorCode`, `GlobalExceptionHandler` | не применяется к `501`, к необработанным `500` и к успешным `Map`-ответам |
| Обработка validation-ошибок с `fieldErrors` | IMPLEMENTED | 2 handler'а | |
| JSON-ответы для 401/403 | IMPLEMENTED | `JsonAuthenticationEntryPoint`, `JsonAccessDeniedHandler` | |
| Catch-all handler для `Exception` | NOT FOUND | — | необработанные исключения дают Spring-дефолт |
| Логирование исключений в handler'ах | NOT FOUND | — | `ErrorResponse.id` нигде не сохраняется |
| Локализация сообщений | NOT FOUND | — | смесь русского и английского |
| Idempotency-Key | NOT FOUND | — | идемпотентность семантическая, не по ключу |
| Retry на сервере | NOT FOUND | — | |

---

## 9. Наблюдаемость и эксплуатация

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| HTTP-логирование запросов/ответов | IMPLEMENTED | `HttpLoggingFilter` | **логирует пароли и токены** |
| Логирование ошибок в сервисах | PARTIAL | 6 вызовов `log.*` | бизнес-события не логируются |
| Health endpoint | NOT FOUND | — | `GET /api/v1/test` не проверяет зависимости |
| Метрики | NOT FOUND | — | Actuator не подключён |
| Tracing / correlation id | NOT FOUND | — | |
| Audit log | NOT FOUND | — | |
| Логи в файл, ротация | NOT FOUND | — | только stdout |
| Фоновые задачи | NOT FOUND | — | ни одной |

---

## 10. Инструментарий разработки

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| OpenAPI / Swagger UI | IMPLEMENTED | springdoc 2.8.3, `@Operation`/`@Tag` на всех контроллерах | публичный доступ к `/v3/api-docs/**` |
| Integration-тесты | IMPLEMENTED | 65 тестов, Testcontainers | |
| Unit-тесты | PARTIAL | 4 теста | |
| Ручные smoke-тесты | PARTIAL | `docs/http-tests/api-smoke-tests.http` | не покрывает folder API и часть file API |
| curl-примеры | STUB | `docs/http-tests/api-curl-examples.md` | файл обрывается на первом заголовке |
| Покрытие кода | NOT FOUND | — | JaCoCo не настроен |
| CI/CD | NOT FOUND | — | каталога `.github` нет |
| Docker | NOT FOUND | — | несмотря на упоминание в `README.md` |
| Скрипт аудита дублей перед миграцией 14 | IMPLEMENTED | `db/audit/check-file-item-folder-checksum-duplicates-before-migration-14.sql` | ручной, вне changelog |
| Документация API | IMPLEMENTED | 4 контракта + overview в `docs/` | качество высокое; расхождения — в `05-api.md` §11 |

---

## 11. Сводная статистика

**[CONFIRMED]** По приведённой матрице (≈140 позиций):

| Статус | Количество | Доля |
| --- | --- | --- |
| IMPLEMENTED | ~58 | ~41% |
| PARTIAL | ~10 | ~7% |
| STUB | 7 | ~5% |
| UNUSED | 4 | ~3% |
| NOT FOUND | ~61 | ~44% |

**[INFERRED]** Ядро продукта (аутентификация, дерево папок, загрузка/скачивание/управление файлами, дедупликация в пределах папки) реализовано и покрыто тестами. Не реализовано в основном то, что относится к «второму кругу»: sharing, корзина, миниатюры, поиск, полноценный протокол синхронизации, эксплуатационная обвязка.

---

## 12. Полный список [UNUSED]-элементов

**[CONFIRMED]** Код существует, но вызовов не обнаружено:

| Элемент | Расположение |
| --- | --- |
| `FileMetadataRepository` (весь интерфейс) | `repository/` — не инжектится нигде |
| `StoredObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc` | помечен TODO как заготовка |
| `FolderRepository.findByUserIdAndParentIsNullAndFolderType` | заменён на `findRootByUserId` |
| `FolderRepository.existsByUserIdAndParentIdAndName` | используется `findByUserIdAndParentIdAndName` |
| `RefreshTokenRepository.findByUserNameAndRevokedAndExpiresAfter` | вызов закомментирован в `JwtService` |
| `EmailRequestRepository.getByCode`, `getByUserIdAndCode` | используется `findByCode` |
| `EmailRequestService.checkAndGenerateCode` | rate-limit не подключён |
| `EmailRequestService.confirmEmail(email, code)` | используется `confirmRegistration(code)` |
| `EmailRequestService.delete(EmailRequest)` | пустое тело |
| `UserRepository.findById(Long)` | переопределение метода `JpaRepository` |
| `UserRegisterMapper.toUserRegisterDto` | обратный маппинг |
| `FileItemRepository.findWithRelationsById` | только в тестах |
| `FileItemService.uploadFile(file, user)` (2 аргумента) | только в тестах |
| `JwtService.isTokenValid` — часть логики | используется, но `roles` в refresh-токене избыточны |
| `Role.ADMIN` | не назначается |
| `ErrorCode.INTERNAL_ERROR` | ни один handler не использует |
| `EntityAlreadyExistException` | не бросается |
| `FileNotFoundException` | не бросается (есть handler) |
| `TickerRequestException` | не бросается; чужеродный класс |
| `ErrorType.PERIOD_EXPIRED`, `DO_NOT_MATCH`, `WRONG_LENGTH` | не используются |
| `EmailContext.attachment`, `fromDisplayName`, `displayName` | заполняются константами-заглушками |
| `ConfigUtil.getStringDefaultDays()`, `SELF_COLOR`, `ACTIVE_REQUESTS_MAX` | не вызываются |
| `DateUtil` (весь класс) | в Java-коде не используется; два метода вызываются из Thymeleaf-фрагмента |
| `EmailRequest.ACTIVE_REQUESTS_MAX` | дублирует `ConfigUtil` |
| `FileItem.deletedAt` | никогда не записывается |
| `templates/fragments/headTag.html` | фрагмент подключён закомментированной строкой |
| `static/css/style.css` | ссылается из шаблонов писем; в письме внешний CSS не работает |
