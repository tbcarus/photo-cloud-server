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
   - если `folderId` не передан, `IMAGE`/`VIDEO` идут в `CAMERA`, для остальных типов возвращается `400`: системной папки для обычных файлов нет.
9. В `CAMERA` проверяет дубликат по `user + folder + checksum`: если такой `FileItem` есть, удаляет temp file и возвращает его. Новый `StoredObject`/`FileItem` не создаются.
10. В `ROOT` и `USER` проверяет имя и его занятость (см. «Правила имен»). Тот же файл с тем же checksum возвращается без новых объектов, занятое имя с другим checksum или папкой даёт `409`.
11. Публикует temp file в финальное physical location без перезаписи: имя резервируется атомарным созданием файла, затем temp переносится поверх собственной заглушки через `ATOMIC_MOVE`. Если путь уже занят, существующий файл не меняется и не удаляется, возвращается `500 INTERNAL_ERROR`. Затем в транзакции под блокировкой структуры пользователя проверки шагов 9–10 повторяются и создается **новый** `StoredObject` и `FileItem`, даже если тот же checksum уже есть у другого файла пользователя. Если параллельный запрос успел первым, собственный опубликованный файл удаляется, а ответ строится как на шагах 9–10.
12. При ошибках удаляет только собственные файлы: temp — до публикации, опубликованный файл — только при достоверном откате транзакции. Если исход фиксации неизвестен или ошибка возникла после фиксации, опубликованный файл сохраняется.

Важно: `user + folder + checksum` определяет дубль только в `CAMERA`.

- Повторная загрузка тех же байтов в `CAMERA` возвращает существующий `FileItem`, даже под другим именем.
- В `ROOT` и `USER` существующий файл возвращается только при совпадении имени и checksum; те же байты под другим именем создают новый файл.
- Тот же checksum в другой папке дублем не считается: создается новый `StoredObject` + новый `FileItem`.
- Одинаковый файл в разных папках = независимые `StoredObject`, что согласуется с delete logic (удаление файла в одной папке не затрагивает другую).

TODO: server-side copy без повторной передачи байтов для ручной загрузки в другую папку — отдельный будущий endpoint/этап.

## Правила имен

- `CAMERA` допускает одинаковые `originalName` в одной папке; имя очищается прежним способом и на занятость не проверяется.
- В `ROOT` и `USER` файлы и папки используют одно пространство имён: имя включает расширение, сравнивается без учета регистра в PostgreSQL (`lower`).
- При конфликте имени возвращается `409 CONFLICT`. Имя `Camera` в `ROOT` зарезервировано без учета регистра и тоже даёт `409`.
- Некорректное имя даёт `400 BAD_REQUEST` с указанием причины и никогда не исправляется автоматически. Требования: не пусто и не только из пробелов; нет `/ \ : * ? " < > |`, управляющих символов и одиночных суррогатов; Unicode NFC; не `.` и `..`; не заканчивается точкой или пробелом; часть до первой точки не совпадает с именем устройства Windows (`CON`, `PRN`, `AUX`, `NUL`, `COM0`–`COM9`, `LPT0`–`LPT9`, варианты с `¹²³`, `CONIN$`, `CONOUT$`); не более 255 байт UTF-8.
- Начальная точка допустима (`.nomedia`).
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
- `Content-Disposition`: `attachment; filename="<FileItem.originalName>"` для ASCII-имени; для остальных имён дополнительно UTF-8: `filename="=?UTF-8?Q?...?="; filename*=UTF-8''<percent-encoded>` (RFC 5987). Значение заголовка содержит только ASCII, исходное имя не меняется.

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
| 400 | blank name, validation error, некорректное имя |
| 404 | файл не найден или чужой |
| 409 | имя занято файлом или папкой либо зарезервировано, кроме `CAMERA`; смена только регистра собственного имени конфликтом не считается |

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
| 400 | validation error, перенос между `CAMERA` и обычной библиотекой в любую сторону |
| 404 | файл или target folder не найдены/чужие |
| 409 | имя занято файлом или папкой в target folder либо зарезервировано |

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
- в `ROOT` и `USER` проверяется только имя, поэтому копия с новым именем в той же папке допустима; имя, в том числе исходное имя Camera-файла, должно соответствовать правилам;
- copy из обычной библиотеки в `CAMERA` запрещён; в `CAMERA` действует правило `user + folder + checksum`, поэтому copy внутри `CAMERA` всегда конфликтует;
- metadata копируется из исходного `FileItem`;
- физический файл копируется во временный файл и публикуется под новым именем в каталог исходного `StoredObject` (`{userId}/camera/{YYYY}` или `{userId}/files`) независимо от target folder; публикация и компенсация такие же, как у upload;
- копировать можно только `StoredObject` текущего пользователя: если исходный `FileItem` ссылается на чужой `StoredObject`, возвращается `403 FORBIDDEN` до операций с файлами.

Response `200 OK`: новый `FileItemDto`.

Ошибки: `400` — некорректное имя или copy из обычной библиотеки в `CAMERA`; `403 FORBIDDEN` — чужой `StoredObject`; `404` — нет исходного файла или target folder; `409 CONFLICT` — имя занято файлом или папкой либо зарезервировано, в `CAMERA` — тот же checksum; `500 INTERNAL_ERROR` — ошибка физического хранилища, в том числе отсутствие байтов источника или занятый физический путь.

### `DELETE /api/v1/files/{id}`

Удаляет файл текущего пользователя. Выполняется под блокировкой строки `ROOT` пользователя, существование файла проверяется после неё; физические байты удаляются после фиксации БД.

Текущая логика:

- если текущий пользователь не владелец `StoredObject`, удаляется только его `FileItem`;
- если текущий пользователь владелец `StoredObject`, удаляются все `FileItem`, которые ссылаются на этот `StoredObject`, затем удаляется `StoredObject`, затем physical file;
- `deletedAt` сейчас не используется как soft delete, удаление hard delete.

Response `204 No Content`. Если файла нет, в том числе после параллельного удаления: `404`.

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
| 409 | имя занято файлом или папкой, имя зарезервировано |
| 413 | multipart/request или streamed file превышает лимит |
| 500 | `INTERNAL_ERROR`: ошибка физического хранилища (в том числе занятый физический путь); временная ошибка, запрос можно повторить. Физические конфликты намеренно не возвращают 409 |

## TODO

- sharing и корректная модель прав для чужих `FileItem`;
- replace/overwrite;
- auto-rename вида `file (1).jpg`;
- soft delete/trash на базе `deletedAt`;
- versioning;
- отдельный link-existing endpoint для создания `FileItem` без повторной загрузки байтов.
