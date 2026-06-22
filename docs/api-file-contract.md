# File API contract

Документ описывает текущий контракт файлов. Базовый путь: `/api/v1/files`. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`.

## Доменные сущности

`FileItem` - логическая запись файла в пользовательской файловой системе:

- принадлежит пользователю;
- находится в одной `Folder`;
- хранит `originalName`, `capturedAt`, `uploadedAt`, `deletedAt`;
- ссылается на один `StoredObject`;
- может иметь `FileMetadata`.

`StoredObject` - физический объект хранения:

- принадлежит пользователю-владельцу физического файла;
- хранит `filePath`, `filename`, `fileExtension`;
- хранит `checksum`, `size`, `detectedMimeType`, `fileType`;
- используется для чтения/скачивания байтов.

Физическое хранилище не является зеркалом папок. Логическое дерево живет в БД через `Folder` и `FileItem`; физические файлы лежат как object storage по `storage.root + filePath + filename`.

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
    "cameraModel": "Pixel",
    "lensModel": null,
    "exposureTime": "1/120",
    "fNumber": 1.8,
    "iso": 100,
    "focalLength": 4.38,
    "latitude": null,
    "longitude": null
  }
}
```

`metadata` может быть `null`.

## Upload pipeline

Upload использует один pipeline для `POST /files` и `POST /files/upload`:

1. Проверяет, что multipart file не пустой.
2. Пишет поток во временный файл в `storage.temp-dir`.
3. По пути считает SHA-256 checksum и размер.
4. Ограничивает размер через `storage.max-file-size-bytes` (`104857600`, 100 MiB).
5. Определяет MIME по содержимому.
6. Выводит `FileType` из MIME.
7. Извлекает metadata, если возможно.
8. Выбирает target folder:
   - если `folderId` передан, использует эту папку;
   - если `folderId` не передан, `IMAGE`/`VIDEO` идут в `CAMERA`, остальные типы в `FILES`.
9. Проверяет дубликат в target folder по `user + folder + checksum`.
   - Если в этой папке уже есть `FileItem` с таким checksum, удаляет temp file и возвращает существующий `FileItem` (upload идемпотентен для папки). Новый `StoredObject`/`FileItem` не создаются.
10. Если дубликата в папке нет, проверяет конфликт `originalName` в папке.
11. Переносит temp file в финальное physical location, создает **новый** `StoredObject` и `FileItem`, даже если тот же checksum уже есть в другой папке пользователя.
12. При ошибках выполняет cleanup temp/final файла.

Важно: дубль файла определяется как `user + folder + checksum` (по `FileItem`).

- Повторная загрузка тех же байтов в ту же папку возвращает существующий `FileItem` — дубль не создается.
- Тот же checksum в другой папке дублем не считается: создается новый `StoredObject` + новый `FileItem`.
- Одинаковый файл в разных папках = независимые `StoredObject`, что согласуется с delete logic (удаление файла в одной папке не затрагивает другую).

TODO: server-side copy без повторной передачи байтов для ручной загрузки в другую папку — отдельный будущий endpoint/этап.

## Правила имен

- `CAMERA` допускает одинаковые `originalName` в одной папке.
- `ROOT`, `FILES` и `USER` не допускают конфликт `originalName` в одной папке без учета регистра.
- При конфликте имени возвращается `409 CONFLICT`.
- `rename` меняет только `FileItem.originalName`.
- `move` меняет только `FileItem.folder`.
- `copy` может принять новое `originalName`.

## Endpoints

### `GET /api/v1/files?folderId=...&page=0&size=10`

Возвращает страницу файлов текущего пользователя. Если `folderId` передан, возвращаются только прямые файлы этой папки.

Сортировка в коде: `capturedAt desc`, `uploadedAt desc`, `id desc`.

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

Если `folderId` чужой или не существует: `404`.

### `POST /api/v1/files`

Старый upload endpoint, сохранен для совместимости.

Request: `multipart/form-data`

| Part/param | Required | Описание |
| --- | --- | --- |
| `file` | yes | файл |
| `folderId` | no | target folder |

Response `200 OK`: `FileItemDto`.

### `POST /api/v1/files/upload`

Явный upload endpoint. Использует тот же pipeline и тот же контракт, что `POST /api/v1/files`.

### `GET /api/v1/files/{id}`

Возвращает metadata/logical card файла текущего пользователя.

Response `200 OK`: `FileItemDto`.

Если файл не найден или принадлежит другому пользователю: `404 FILE_ITEM_NOT_FOUND`.

### `GET /api/v1/files/{id}/download`

Скачивает физический файл, связанный с `FileItem`.

Response `200 OK`:

- body: raw bytes;
- `Content-Type`: `StoredObject.detectedMimeType`;
- `Content-Disposition`: `attachment; filename="<FileItem.originalName>"`.

Если `FileItem` не найден, чужой, либо физический файл отсутствует/не читается: `404`.

### `PATCH /api/v1/files/{id}`

Переименовывает логическую запись, не трогая физический файл.

Request:

```json
{
  "originalName": "new-name.jpg"
}
```

Response `200 OK`: обновленный `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | blank name, validation error |
| 404 | файл не найден или чужой |
| 409 | имя занято в target folder, кроме `CAMERA` |

### `POST /api/v1/files/{id}/move`

Перемещает логическую запись в другую папку. Физический файл не перемещается.

Request:

```json
{
  "targetFolderId": 12
}
```

Response `200 OK`: обновленный `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | validation error |
| 404 | файл или target folder не найдены/чужие |
| 409 | имя файла конфликтует в target folder, кроме `CAMERA` |

### `POST /api/v1/files/{id}/copy`

Создает физическую копию файла и новый `StoredObject`, затем создает новый `FileItem`.

Request:

```json
{
  "targetFolderId": 12,
  "originalName": "copy.jpg"
}
```

`targetFolderId` optional: если не передан, копия создается в исходной папке. `originalName` optional: если не передан или blank, используется имя исходного файла.

Важно:

- copy dedup не использует;
- даже при одинаковом checksum создается новый `StoredObject`;
- copy подчиняется правилу `user + folder + checksum`: если в target folder уже есть `FileItem` с таким checksum, возвращается `409 CONFLICT` и дубль не создается. Это значит, что copy в исходную папку (тот же checksum) всегда конфликтует — для копии нужен `targetFolderId` другой папки;
- metadata копируется из исходного `FileItem`;
- физический файл копируется в object storage.

Response `200 OK`: новый `FileItemDto`.

### `DELETE /api/v1/files/{id}`

Удаляет файл текущего пользователя.

Текущая логика:

- если текущий пользователь не владелец `StoredObject`, удаляется только его `FileItem`;
- если текущий пользователь владелец `StoredObject`, удаляются все `FileItem`, которые ссылаются на этот `StoredObject`, затем удаляется `StoredObject`, затем physical file;
- `deletedAt` сейчас не используется как soft delete, удаление hard delete.

Response `204 No Content`.

TODO: при появлении sharing нужно пересмотреть удаление чужих `FileItem` владельцем физического объекта.

### `GET /api/v1/files/checksums`

Старый endpoint списка checksum текущего пользователя. Возвращает checksum по `FileItem`, а не минимальный pre-check контракт.

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

Для нового batch pre-check используется `POST /api/v1/files/checksums/exists`, описанный в `api-checksum-sync-contract.md`.

## Ошибки

| Status | Когда |
| --- | --- |
| 400 | validation error, malformed body, empty file, слишком большой batch в checksum exists, illegal argument |
| 401 | нет/невалидный access token |
| 404 | файл/папка не найдены или принадлежат другому пользователю; physical file отсутствует при download |
| 409 | конфликт имени файла |
| 413 | multipart/request или streamed file превышает лимит |

## TODO

- sharing и корректная модель прав для чужих `FileItem`;
- replace/overwrite;
- auto-rename вида `file (1).jpg`;
- soft delete/trash на базе `deletedAt`;
- versioning;
- отдельный link-existing endpoint для создания `FileItem` без повторной загрузки байтов.
