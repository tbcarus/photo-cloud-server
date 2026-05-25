# File API contract

Документ описывает текущий контракт файлов. Базовый путь: `/api/v1/files`. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`.

## Доменные сущности

**`FileItem`** — логическая запись файла в пользовательской файловой системе:

| Field | Тип | Описание |
| --- | --- | --- |
| `id` | Long | первичный ключ |
| `user` | User | владелец записи |
| `folder` | Folder | папка, в которой лежит файл |
| `storedObject` | StoredObject | ссылка на физический объект |
| `originalName` | String (max 255) | имя файла, видимое пользователю |
| `capturedAt` | LocalDateTime | время съемки или время upload |
| `uploadedAt` | LocalDateTime | время создания записи |
| `deletedAt` | LocalDateTime | зарезервировано для soft delete, сейчас не используется |
| `metadata` | FileMetadata | optional EXIF/media metadata |

**`StoredObject`** — физический объект хранения:

| Field | Тип | Описание |
| --- | --- | --- |
| `id` | Long | первичный ключ |
| `user` | User | владелец физического файла |
| `filePath` | String (max 1024) | путь внутри object storage |
| `filename` | String (max 255) | sanitized физическое имя файла |
| `fileExtension` | String (max 20) | расширение |
| `checksum` | String (64 hex) | SHA-256 |
| `size` | Long | размер в байтах |
| `detectedMimeType` | String (max 100) | MIME, определенный по содержимому |
| `fileType` | FileType | `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `ARCHIVE`, `OTHER` |
| `createdAt` | LocalDateTime | дата создания |

Физическое хранилище не является зеркалом папок. Логическое дерево живет в БД через `Folder` и `FileItem`; физические файлы лежат в object storage по пути `storage.root / filePath / filename`.

Один `StoredObject` может быть использован несколькими `FileItem` (deduplication при upload).

## DTO

`FileItemDto`:

```json
{
  "id": 42,
  "folderId": 7,
  "originalFilename": "photo.jpg",
  "mimeType": "image/jpeg",
  "size": 123456,
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "fileType": "IMAGE",
  "capturedAt": "2026-05-17T10:15:30",
  "uploadedAt": "2026-05-17T10:16:00",
  "deletedAt": null,
  "metadata": {
    "width": 4032,
    "height": 3024,
    "durationSec": null,
    "cameraMake": "Google",
    "cameraModel": "Pixel 7",
    "lensModel": null,
    "exposureTime": "1/120",
    "fNumber": 1.8,
    "iso": 100,
    "focalLength": 4.38,
    "latitude": 55.751244,
    "longitude": 37.618423
  }
}
```

`metadata` может быть `null`, если extraction не дала результата.

## Upload pipeline

Один pipeline используется для `POST /files` и `POST /files/upload`:

1. Проверяет, что multipart file не пустой.
2. Пишет входящий поток во временный файл в `storage.temp-dir`.
3. Во время записи считает SHA-256 checksum и размер в байтах.
4. Ограничивает размер через `storage.max-file-size-bytes` (104 857 600 байт = 100 MiB).
5. Определяет MIME по содержимому временного файла (Apache Tika).
6. Определяет `FileType` из MIME.
7. Извлекает media metadata (Drew Noakes library).
8. Определяет target folder:
   - если `folderId` передан — использует эту папку;
   - если `folderId` не передан — `IMAGE`/`VIDEO` идут в `CAMERA`, остальные типы в `FILES`.
9. Проверяет конфликт `originalName` в папке.
10. Ищет существующий `StoredObject` текущего пользователя по checksum (dedup).
11. Если `StoredObject` найден: удаляет temp file, создает новый `FileItem` на существующий `StoredObject`.
12. Если `StoredObject` не найден: переносит temp file в финальное место в object storage, создает `StoredObject` и `FileItem`.
13. При любой ошибке выполняет cleanup temp file и финального файла, если он был перенесен.

Upload dedup работает в рамках одного пользователя: проверяется `StoredObject(user_id, checksum)`.
Повторная загрузка тех же байтов в допустимую папку создает новый `FileItem`, указывающий на существующий `StoredObject`.

## Правила имен файлов

- `CAMERA` допускает одинаковые `originalName` в одной папке.
- `ROOT`, `FILES` и `USER` не допускают конфликт `originalName` в одной папке (без учета регистра).
- При конфликте: `409 CONFLICT`.
- `rename` меняет только `FileItem.originalName`; физический `StoredObject.filename` не затрагивается.
- `move` меняет только `FileItem.folder`; физический файл не перемещается.
- `copy` может принять новое `originalName`; создает независимую физическую копию.

## Endpoints

### `GET /api/v1/files?folderId=...&page=0&size=10`

Возвращает страницу файлов текущего пользователя. Если `folderId` передан — только прямые файлы этой папки.

Сортировка: `capturedAt desc`, затем `uploadedAt desc`, затем `id desc`.

Response `200 OK`:

```json
{
  "items": [
    {
      "id": 42,
      "folderId": 7,
      "originalFilename": "photo.jpg",
      "mimeType": "image/jpeg",
      "size": 123456,
      "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "fileType": "IMAGE",
      "capturedAt": "2026-05-17T10:15:30",
      "uploadedAt": "2026-05-17T10:16:00",
      "deletedAt": null,
      "metadata": null
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

Если `folderId` не существует или принадлежит другому пользователю: `404`.

### `POST /api/v1/files`

Загружает файл. Сохранен для обратной совместимости. Использует тот же pipeline, что `POST /api/v1/files/upload`.

Request: `multipart/form-data`

| Part/param | Required | Описание |
| --- | --- | --- |
| `file` | yes | файл |
| `folderId` | no | id целевой папки; если не передан — используется CAMERA или FILES |

Response `201 Created`: `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | пустой файл, validation error |
| 409 | конфликт имени в папке |
| 413 | файл превышает лимит (110 MB multipart, 100 MiB application-level) |

### `POST /api/v1/files/upload`

Явный upload endpoint. Идентичен `POST /api/v1/files` по контракту и pipeline.

Response `201 Created`: `FileItemDto`.

### `GET /api/v1/files/{id}`

Возвращает metadata/логическую карточку файла текущего пользователя.

Response `200 OK`: `FileItemDto`.

Если файл не найден или принадлежит другому пользователю: `404 FILE_ITEM_NOT_FOUND`.

### `GET /api/v1/files/{id}/download`

Скачивает физический файл, связанный с `FileItem`.

Response `200 OK`:

- body: raw bytes;
- `Content-Type`: `StoredObject.detectedMimeType`;
- `Content-Disposition`: `attachment; filename="<FileItem.originalName>"`.

Если `FileItem` не найден, чужой или физический файл отсутствует/недоступен: `404`.

### `PATCH /api/v1/files/{id}`

Переименовывает логическую запись. Физический файл и `StoredObject` не затрагиваются.

Request:

```json
{
  "originalName": "new-name.jpg"
}
```

Validation: `originalName` — not blank, max 255.

Response `200 OK`: обновленный `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | blank name, validation error |
| 404 | файл не найден или принадлежит другому пользователю |
| 409 | имя занято в папке, кроме случая CAMERA |

### `POST /api/v1/files/{id}/move`

Перемещает логическую запись в другую папку. Физический файл остается на месте.

Request:

```json
{
  "targetFolderId": 12
}
```

Validation: `targetFolderId` — not null.

Response `200 OK`: обновленный `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | validation error |
| 404 | файл или target folder не найдены/чужие |
| 409 | конфликт имени файла в target folder, кроме CAMERA |

### `POST /api/v1/files/{id}/copy`

Создает полную физическую копию файла: новые байты в object storage, новый `StoredObject`, новый `FileItem`.

Request:

```json
{
  "targetFolderId": 12,
  "originalName": "copy.jpg"
}
```

- `targetFolderId` — optional; если не передан, копия создается в исходной папке.
- `originalName` — optional; если не передан или blank, используется имя исходного `FileItem`.

Важные отличия от upload:

- copy **не использует** dedup по checksum;
- даже при одинаковом checksum создается новый независимый `StoredObject`;
- metadata копируется из исходного `FileItem`;
- физический файл копируется в object storage.

Response `200 OK`: `FileItemDto` нового файла.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | validation error |
| 404 | исходный файл или target folder не найдены/чужие |
| 409 | конфликт имени в target folder |

### `DELETE /api/v1/files/{id}`

Удаляет файл текущего пользователя.

Логика:

- если текущий пользователь **не является** владельцем `StoredObject` — удаляется только его `FileItem`;
- если текущий пользователь **является** владельцем `StoredObject` — удаляются все `FileItem`, ссылающиеся на этот `StoredObject`, затем сам `StoredObject`, затем физический файл.

`deletedAt` сейчас не используется. Удаление — hard delete.

Response `204 No Content`.

Ошибки:

| Status | Когда |
| --- | --- |
| 404 | файл не найден или принадлежит другому пользователю |

TODO: при появлении sharing нужно пересмотреть удаление чужих `FileItem` владельцем `StoredObject`.

### `GET /api/v1/files/checksums`

Возвращает список checksum всех файлов текущего пользователя. Старый endpoint; возвращает данные по `FileItem`, а не минимальный pre-check контракт.

Response `200 OK`:

```json
[
  {
    "id": 42,
    "originalFilename": "photo.jpg",
    "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
]
```

Для нового batch pre-check используется `POST /api/v1/files/checksums/exists` (описан в `api-checksum-sync-contract.md`).

## Ошибки

| Status | Когда |
| --- | --- |
| 400 | validation error, malformed body, empty file, illegal argument |
| 401 | нет или невалидный access token |
| 404 | файл/папка не найдены или принадлежат другому пользователю; physical file отсутствует при download |
| 409 | конфликт имени файла в папке |
| 413 | multipart/request или application-level лимит размера превышен |

## TODO

- sharing и корректная модель прав для чужих `FileItem`;
- replace/overwrite;
- auto-rename вида `file (1).jpg`;
- soft delete/trash на базе `FileItem.deletedAt`;
- versioning;
- link-existing endpoint для создания `FileItem` на существующий `StoredObject` без повторной загрузки байтов.
