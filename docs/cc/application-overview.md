# Application overview

Этот документ — карта текущей серверной реализации `photo-cloud-server`. Это не API-контракт; точные endpoint-ы описаны в отдельных `api-*-contract.md`.

## 1. Назначение приложения

`photo-cloud-server` — серверная часть личного облачного файлового хранилища. Приложение предоставляет REST API для:

- регистрации и авторизации пользователей;
- загрузки, скачивания и управления файлами;
- логической организации файлов по папкам;
- checksum-проверки перед upload (pre-check для Android-клиента).

Стек: Java 17, Spring Boot 3.4.4, PostgreSQL, Liquibase, Gradle.

## 2. Основные доменные сущности

### User

- Email используется как username/login; при регистрации приводится к lowercase.
- Пользователь создается `enabled=false` и переходит в `enabled=true` после подтверждения email.
- Роли: `USER` (обычный) и `ADMIN`.
- `lastLoginAt` обновляется при каждом login.
- `banned=true` блокирует вход наравне с `enabled=false`.

### Folder

- Логическая папка пользователя в БД.
- Имеет `parent`; у ROOT `parent` равен null.
- Типы: `ROOT`, `CAMERA`, `FILES`, `USER`.
- Дерево папок полностью живет в БД; дочерние папки не хранятся коллекцией в entity.

### FileItem

- Логическая запись файла в пользовательской структуре.
- Содержит `originalName` (пользовательское имя), `capturedAt`, `uploadedAt`, `deletedAt`.
- Ссылается на физический `StoredObject`.
- Может иметь `FileMetadata`.

### StoredObject

- Физический объект хранения; содержит `filePath`, `filename`, `fileExtension`, `checksum`, `size`, `detectedMimeType`, `fileType`.
- Может быть использован несколькими `FileItem` (deduplication при upload в рамках одного пользователя).
- Владелец `StoredObject` — пользователь, первый загрузивший эти байты.

### FileMetadata

- Optional; привязана к `FileItem` через OneToOne.
- Хранит: `width`, `height`, `durationSec`, `cameraMake`, `cameraModel`, `lensModel`, `exposureTime`, `fNumber`, `iso`, `focalLength`, `latitude`, `longitude`.
- Извлекается при upload через библиотеку Drew Noakes.

### RefreshToken

- Запись о выданном refresh token в таблице `refresh_token`.
- Содержит `token` (JWT), `userName` (email), `expires`, `revoked`, `revokedAt`.
- При logout/logout-all/logout-others устанавливается `revoked=true`.
- Refresh token endpoint проверяет флаг `revoked` перед выдачей нового access token.

### EmailRequest

- Запись о выданном email-коде (UUID).
- Типы: `ACTIVATE` (подтверждение регистрации), `PASSWORD_RESET`.
- Лимит: не более 3 запросов за 3 дня на пользователя + тип.
- Срок действия кода: 7 дней.
- После использования помечается как использованный.

## 3. Физическое хранение

Физическое хранение работает как object storage на файловой системе, а не как зеркало пользовательских папок.

| Config | Значение |
| --- | --- |
| `storage.root` | `${STORAGE_ROOT:storage}` |
| `storage.temp-dir` | `${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp}` |
| `storage.max-file-size-bytes` | `104857600` (100 MiB) |
| `spring.servlet.multipart.max-file-size` | `110MB` |
| `spring.servlet.multipart.max-request-size` | `110MB` |

`filePath` и `filename` в `StoredObject` генерируются автоматически. Пользовательское имя живет в `FileItem.originalName`. Поэтому move и rename в логической файловой системе не требуют перемещения или переименования физического файла.

## 4. Логическая файловая система

У каждого пользователя ровно один `ROOT`. Он создается лениво при первом обращении.

Под ROOT лениво создаются системные папки:

- `Camera` (тип `CAMERA`) — default folder для `IMAGE` и `VIDEO`.
- `Files` (тип `FILES`) — default folder для `AUDIO`, `DOCUMENT`, `ARCHIVE`, `OTHER`.

`USER`-папки можно создавать в `ROOT` или внутри других `USER`-папок.

Правила:

| Действие | ROOT | CAMERA | FILES | USER |
| --- | --- | --- | --- | --- |
| Rename | ❌ | ❌ | ❌ | ✅ |
| Move | ❌ | ❌ | ❌ | ✅ |
| Delete | ❌ | ❌ | ❌ | ✅ (только пустая) |
| Create child folder | n/a | ❌ | ❌ | ✅ |
| Upload files | ✅ | ✅ | ✅ | ✅ |
| Unique filenames | ✅ | ❌ (дубликаты допустимы) | ✅ | ✅ |

## 5. Загрузка файлов

Upload pipeline (один для `POST /files` и `POST /files/upload`):

1. Проверить, что multipart file не пустой.
2. Записать поток во temp file в `storage.temp-dir`.
3. Во время записи вычислить SHA-256 checksum и размер.
4. Ограничить размер через `storage.max-file-size-bytes` (100 MiB).
5. Определить MIME по содержимому temp file (Apache Tika).
6. Определить `FileType` из MIME.
7. Извлечь media metadata (Drew Noakes).
8. Определить target folder: переданный `folderId` или default `CAMERA`/`FILES`.
9. Проверить конфликт `originalName` в папке.
10. Искать dedup: `StoredObject(user_id, checksum)`.
11. Если найден: удалить temp, создать `FileItem` на существующий `StoredObject`.
12. Если не найден: переместить temp в финальный путь, создать `StoredObject` и `FileItem`.
13. При ошибках: cleanup temp и финального файла.

Параллельная загрузка одинаковых байтов обрабатывается через try-catch на duplicate key (race-safe).

## 6. Операции с файлами

**list** — возвращает страницу `FileItemDto`; можно ограничить по `folderId`; сортировка: `capturedAt desc → uploadedAt desc → id desc`.

**upload** — создает `FileItem`; может переиспользовать существующий `StoredObject` по checksum.

**copy** — создает физическую копию байтов в object storage; создает новый `StoredObject` и `FileItem`; dedup намеренно не используется.

**move** — меняет только `FileItem.folder`; физический файл не трогает.

**rename** — меняет только `FileItem.originalName`; `StoredObject.filename` не меняется.

**delete:**
- если текущий пользователь не владелец `StoredObject` — удаляется только его `FileItem`;
- если текущий пользователь владелец `StoredObject` — удаляются все `FileItem` на этот объект, затем `StoredObject`, затем physical file;
- сейчас hard delete; `deletedAt` зарезервирован для будущего trash.

## 7. Checksum sync

`POST /api/v1/files/checksums/exists` нужен Android-клиенту как pre-check перед upload. Клиент отправляет batch SHA-256, сервер отвечает, какие checksum уже есть в `StoredObject` пользователя.

Текущие ограничения:

- это не полноценная синхронизация;
- `FileItem` и `Folder` не учитываются;
- response минимален: `existing` и `missing`;
- link-existing endpoint отсутствует — сервер не создает `FileItem` автоматически;
- device model и sync sessions не реализованы.

Batch limit: 500 (конфигурируется через `sync.checksum-exists.max-batch-size`).

## 8. Auth / Security

Приложение stateless по HTTP-сессиям, использует JWT.

| Токен | Тип | Срок | Хранение |
| --- | --- | --- | --- |
| Access token | JWT, `token_type=ACCESS` | 20 минут | только у клиента |
| Refresh token | JWT, `token_type=REFRESH` | 7 дней | у клиента + в БД (`refresh_token`) |

JWT подписывается симметричным ключом из `token.signing.key` (env var `JWT_KEY`).

`JwtAuthenticationFilter` проверяет `Authorization: Bearer <token>`:

- Извлекает email из subject.
- Проверяет, что тип токена `ACCESS` (refresh token в этот endpoint не принимается).
- При `ExpiredJwtException` или невалидном токене очищает SecurityContext и возвращает `401`.

Публичные endpoint-ы:

- `/api/v1/test`
- `/api/v1/auth/register`, `/confirm`, `/resend`
- `/api/v1/auth/login`, `/refresh-token`
- `/api/v1/auth/password/reset/**`
- `/swagger-ui/**`, `/v3/api-docs/**`

Все остальные endpoint-ы — `authenticated()`.

`401` — отсутствующий/невалидный/истекший access token, неверный логин/пароль, invalid refresh token.
`403` — revoked refresh token или попытка logout чужого токена.

## 9. Текущие ограничения и TODO

- sharing и модель прав доступа к файлам/папкам;
- soft delete/trash на базе `FileItem.deletedAt`;
- recursive delete folder с учетом sharing;
- replace/overwrite при конфликте имени;
- auto-rename вида `file (1).jpg`;
- versioning файлов;
- device sync и полноценные sync sessions;
- link-existing: создание `FileItem` на существующий `StoredObject` без upload;
- отзыв refresh token после смены пароля;
- resend для регистрации и password reset (stub 501).

## 10. Важные архитектурные решения

- БД — источник правды логической структуры файлов и папок.
- Физическое хранилище — object storage; путь хранится в `StoredObject`, не повторяет дерево папок.
- `FileItem` отделен от `StoredObject`: логическая структура меняется (move/rename) без физического перемещения.
- Upload использует dedup по checksum в рамках пользователя; повторная загрузка тех же байтов экономит место.
- Copy создает независимую физическую копию и новый `StoredObject`; dedup намеренно не применяется.
- `CAMERA` допускает одинаковые `originalName`; `ROOT`, `FILES`, `USER` — нет.
- ROOT и системные папки создаются лениво, а не при регистрации.
- Доступ к чужим ресурсам возвращает `404`, а не `403` (не раскрывает факт существования).
