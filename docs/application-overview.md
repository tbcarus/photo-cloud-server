# Application overview

Этот документ - карта текущей серверной реализации `photo-cloud-server`. Это не API-контракт; точные endpoint-ы описаны в отдельных `api-*-contract.md`.

## 1. Назначение приложения

`photo-cloud-server` - серверная часть личного облачного файлового хранилища. Приложение предоставляет REST API для регистрации и авторизации пользователей, загрузки и скачивания файлов, логической организации файлов по папкам и первичной checksum-проверки для Android-клиента перед upload.

## 2. Основные доменные сущности

`User`:

- email используется как username/login;
- при регистрации email приводится к lowercase;
- пользователь создается disabled и становится enabled после подтверждения email;
- роли хранятся как `USER`/`ADMIN`;
- `lastLoginAt` обновляется при login.

`Folder`:

- логическая папка пользователя;
- имеет `parent`;
- типы: `ROOT`, `CAMERA`, `FILES`, `USER`;
- дерево папок хранится в БД.

`FileItem`:

- логическая запись файла в пользовательской структуре;
- содержит `originalName`, `folder`, `capturedAt`, `uploadedAt`, `deletedAt`;
- ссылается на физический `StoredObject`.

`StoredObject`:

- физический объект хранения;
- содержит `filePath`, `filename`, `fileExtension`, `checksum`, `size`, `detectedMimeType`, `fileType`;
- может быть использован несколькими `FileItem`.

`FileMetadata`:

- optional metadata для `FileItem`;
- хранит размеры, длительность, камеру, EXIF-поля и координаты, если их удалось извлечь.

## 3. Физическое хранение

Физическое хранение работает как object storage на файловой системе, а не как зеркало пользовательских папок.

Основные настройки:

| Config | Текущее значение |
| --- | --- |
| `storage.root` | `${STORAGE_ROOT:storage}` |
| `storage.temp-dir` | `${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp}` |
| `storage.max-file-size-bytes` | `104857600` |
| `spring.servlet.multipart.max-file-size` | `110MB` |
| `spring.servlet.multipart.max-request-size` | `110MB` |

`filePath` и `filename` генерируются для физического объекта. Пользовательское имя живет отдельно в `FileItem.originalName`. Поэтому move/rename в логической файловой системе не требуют перемещения или переименования физического файла.

## 4. Логическая файловая система

У каждого пользователя есть один `ROOT`. Он создается лениво.

Под `ROOT` лениво создаются системные папки:

- `Camera` (`CAMERA`) - default folder для `IMAGE` и `VIDEO`;
- `Files` (`FILES`) - default folder для `AUDIO`, `DOCUMENT`, `ARCHIVE`, `OTHER`.

Пользовательские папки (`USER`) можно создавать в `ROOT` и внутри других `USER`-папок. Внутри `CAMERA` и `FILES` создавать папки нельзя. Системные папки нельзя rename/move/delete.

Удаление папки сейчас разрешено только для пустой `USER`-папки.

## 5. Загрузка файлов

Upload pipeline:

1. Multipart file проверяется на пустоту.
2. Поток пишется во временный файл.
3. Во время записи считается SHA-256 и размер.
4. Размер ограничен `storage.max-file-size-bytes`.
5. MIME определяется по содержимому временного файла.
6. MIME преобразуется в `FileType`.
7. Извлекаются metadata.
8. Определяется target folder: переданный `folderId` или default `CAMERA`/`FILES`.
9. Проверяется конфликт имени в папке.
10. Выполняется dedup по `StoredObject(user_id, checksum)`.
11. Если физический объект уже есть, создается новый `FileItem` на существующий `StoredObject`.
12. Если физического объекта нет, temp file переносится в final path, создаются `StoredObject` и `FileItem`.
13. При ошибках выполняется cleanup временного или уже перенесенного файла.

## 6. Операции с файлами

`list`:

- возвращает страницу `FileItemDto`;
- можно ограничить список прямыми файлами конкретной папки через `folderId`;
- сортировка: `capturedAt desc`, `uploadedAt desc`, `id desc`.

`upload`:

- создает логическую запись `FileItem`;
- может переиспользовать существующий `StoredObject` по checksum.

`copy`:

- создает физическую копию файла;
- создает новый `StoredObject`;
- создает новый `FileItem`;
- dedup намеренно не использует.

`move`:

- меняет только `FileItem.folder`;
- физический файл не трогает.

`rename`:

- меняет только `FileItem.originalName`;
- физический `StoredObject.filename` не меняется.

`delete`:

- если текущий пользователь не владелец `StoredObject`, удаляется только его `FileItem`;
- если текущий пользователь владелец `StoredObject`, удаляются все `FileItem` на этот объект, затем `StoredObject`, затем physical file;
- сейчас это hard delete, не trash.

## 7. Checksum sync

`POST /api/v1/files/checksums/exists` нужен Android-клиенту как pre-check перед upload. Клиент отправляет batch SHA-256 checksum, сервер отвечает, какие checksum уже есть в `StoredObject` текущего пользователя.

Ограничения:

- это не полноценная синхронизация;
- `FileItem` и `Folder` не учитываются;
- response минимален: `existing` и `missing`;
- link-existing endpoint пока отсутствует;
- device model и sync sessions пока отсутствуют.

## 8. Auth/security

Приложение stateless по HTTP-сессиям и использует JWT.

- Access token: JWT с `token_type=ACCESS`, roles и subject=email, срок 20 минут.
- Refresh token: JWT с `token_type=REFRESH`, хранится в таблице refresh tokens, срок 7 дней.
- Refresh token можно отозвать через logout/logout-all/logout-others.
- Защищенные endpoint-ы требуют `Authorization: Bearer <accessToken>`.
- Публичные endpoint-ы ограничены test, register, login, refresh и password reset flow.

`401` используется для отсутствующего/невалидного access token и invalid credentials/refresh token. `403` используется для revoked refresh token и refresh token ownership error.

## 9. Текущие ограничения и TODO

- sharing и модель прав доступа;
- soft delete/trash на базе `FileItem.deletedAt`;
- recursive delete folder с учетом sharing;
- replace/overwrite;
- auto-rename при конфликте имени;
- versioning;
- device sync и полноценные sync sessions;
- link-existing для создания `FileItem` на существующий `StoredObject` без upload.

## 10. Важные архитектурные решения

- БД является источником логической структуры файлов и папок.
- Физическое хранилище является object storage и не повторяет дерево папок.
- `FileItem` отделен от `StoredObject`, поэтому логическая структура может меняться без physical move.
- Upload использует dedup по checksum в рамках пользователя.
- Copy создает независимую физическую копию и новый `StoredObject`.
- Move и rename не трогают физический файл.
- `CAMERA` допускает одинаковые имена файлов.
- `ROOT`, `FILES` и `USER` не допускают одинаковые `originalName` в одной папке без учета регистра.
