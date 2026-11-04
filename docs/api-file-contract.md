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
- хранит `filePath`, `filename`, `fileExtension` только для файлов `CAMERA`;
- хранит `checksum`, `size`, `detectedMimeType`, `fileType`;
- используется для чтения/скачивания байтов.

Файлы `ROOT` и `USER` лежат в дереве `{storage.root}/{userId}/files/{папки}/{originalName}` под исходными именами, путь вычисляется из `Folder` и не хранится. Файлы `CAMERA` лежат в `{userId}/camera/{YYYY}` по физическому ключу с UUID.

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
   - если `folderId` передан, использует эту папку; системный контейнер `AUTO_UPLOADS` файлов не принимает — `400`;
   - проверяет доступ к активному дереву по `backupSource` (см. `api-folder-contract.md`, «Активное дерево источника»): без параметра запись в активное дерево — `409 FOLDER_MANAGED`, с параметром — только в дерево источника с этим UUID, иначе `409 FOLDER_MANAGED`;
   - если `folderId` не передан, `IMAGE`/`VIDEO` идут в `CAMERA`, для остальных типов возвращается `400`: системной папки для обычных файлов нет.
9. В `CAMERA` проверяет дубликат по `user + folder + checksum`: если такой `FileItem` есть, удаляет temp file и возвращает его. Новый `StoredObject`/`FileItem` не создаются.
10. В `ROOT` и `USER` проверяет имя и его занятость (см. «Правила имен»). Тот же файл с тем же checksum возвращается без новых объектов, занятое имя с другим checksum или папкой даёт `409`.
11. В транзакции под блокировкой строки `ROOT` повторяет проверки, в том числе `backupSource` — отключённый за это время источник даёт `409 FOLDER_MANAGED`, байты не публикуются, сохраняет `StoredObject` и `FileItem` и публикует temp file в финальное physical location без перезаписи: имя резервируется атомарным созданием файла, затем temp переносится поверх собственной заглушки через `ATOMIC_MOVE`. Путь, занятый на диске без записи в БД, не присваивается и не заменяется: `409` в `ROOT`/`USER`, `500 INTERNAL_ERROR` в `CAMERA`.
12. При ошибках удаляет только собственный temp. Если исход фиксации неизвестен, опубликованный файл сохраняется: лучше файл без записи, чем запись без байтов.

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

### `GET /api/v1/files?folderId=...&page=0&size=10&sort=name`

Возвращает страницу файлов текущего пользователя. Если `folderId` передан, возвращаются только прямые файлы этой папки.

Необязательный параметр `sort`:

| Значение | Порядок |
| --- | --- |
| не передан | `capturedAt desc`, `uploadedAt desc`, `id desc` |
| `name` | `lower(originalName COLLATE "ru-x-icu")` по возрастанию, затем `id asc` |

Сортирует PostgreSQL до применения LIMIT/OFFSET. Порядок при `sort=name` не зависит от локали БД: регистр не учитывается, кириллица идёт по алфавиту и раньше латиницы, `Ё` сортируется как вариант `Е`. Для работы нужны collation `ru-x-icu` (PostgreSQL собран с ICU) и кодировка БД, отличная от `SQL_ASCII`. Другое значение `sort`, в том числе пустое, даёт `400 BAD_REQUEST`.

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
| `backupSource` | no | UUID активного источника; передаёт только механизм автоматического резервирования, всегда вместе с `folderId` |

Response `200 OK`: `FileItemDto`.

Некорректный `backupSource` либо `backupSource` без `folderId`: `400` до записи временного файла и выбора папки, Camera не создаётся.

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

### `GET /api/v1/files/{id}/thumbnail`

JPEG-миниатюра изображения. Создаётся при запросе, на Server не сохраняется. Query-параметров нет.

Request headers:

- `Authorization: Bearer <accessToken>` — обязателен;
- `If-None-Match` — необязателен: ETag из прежнего ответа (допускается `W/` и список).

Порядок обработки: владелец (как у download) → формат по `StoredObject.detectedMimeType` → сравнение ETag → генерация.

Response `200 OK`:

- body: JPEG, длинная сторона не больше `thumbnail.max-side` (320 px), пропорции сохранены, меньшие изображения не увеличиваются; учтена EXIF Orientation 1–8; прозрачность заменена белым фоном; метаданных исходника (EXIF, GPS) нет;
- `Content-Type: image/jpeg`;
- `ETag: "<32 hex>"` — непрозрачное значение от checksum содержимого, версии алгоритма и `thumbnail.max-side`;
- `Cache-Control: max-age=86400, private` (`thumbnail.cache-max-age`); `Pragma`/`Expires` не отправляются.

Response `304 Not Modified`: `If-None-Match` содержит текущий ETag. Тела нет, оригинал не читается; `ETag` и `Cache-Control` те же. `If-None-Match: *` совпадением не считается.

Поддерживаемые MIME: `image/jpeg`, `image/png`, `image/webp` (кроме анимированного).

| Status | Code | Когда |
| --- | --- | --- |
| 401 | — | нет/невалидный access token |
| 404 | `FILE_ITEM_NOT_FOUND` | `FileItem` не найден, чужой (в том числе при совпадающем `If-None-Match`), physical file отсутствует |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | другой MIME (HEIC/HEIF, AVIF, GIF, видео, PDF и т. п.), анимированный WebP |
| 422 | `IMAGE_UNREADABLE` | изображение повреждено или не декодируется, нулевые размеры |
| 422 | `IMAGE_TOO_LARGE` | ширина×высота больше `thumbnail.max-source-pixels`; для WebP lossless или с прозрачностью — больше `thumbnail.max-full-decode-pixels` |
| 503 | `SERVICE_BUSY` | заняты все `thumbnail.max-concurrent` слота генерации; `Retry-After: 1`, очереди нет |
| 500 | `INTERNAL_ERROR` | ошибка чтения хранилища |

Ошибки — JSON `ErrorResponse` с `Content-Type: application/json`, в том числе при `Accept: image/*`.

### `PATCH /api/v1/files/{id}`

Переименовывает файл. В `ROOT`/`USER` вместе с записью меняется физическое имя, в том числе при смене только регистра; в `CAMERA` физический файл не меняется. ID сохраняется.

Request:

```json
{
  "originalName": "new-name.jpg",
  "backupSource": null
}
```

`backupSource` необязателен; передаёт только механизм автоматического резервирования для файла активного дерева своего источника (архивирование прежней версии).

Response `200 OK`: обновленный `FileItemDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | blank name, validation error, некорректное имя |
| 404 | файл не найден или чужой |
| 409 | имя занято файлом или папкой либо зарезервировано, кроме `CAMERA`; смена только регистра собственного имени конфликтом не считается |
| 409 `FOLDER_MANAGED` | файл в активном дереве без `backupSource` или с другим UUID; `backupSource` передан для файла вне активного дерева |

### `POST /api/v1/files/{id}/move`

Перемещает файл в другую папку вместе с физическим файлом, без замены существующих объектов. ID сохраняется.

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
| 400 | validation error, перенос между `CAMERA` и обычной библиотекой в любую сторону, target=`AUTO_UPLOADS` |
| 404 | файл или target folder не найдены/чужие |
| 409 | имя занято файлом или папкой в target folder либо зарезервировано |
| 409 `FOLDER_MANAGED` | файл или target folder в активном дереве источника |

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
- физический файл копируется во временный файл вне блокировки и публикуется в дереве целевой папки под именем копии, без UUID, в том числе при копировании из `CAMERA`; удаление копии не затрагивает источник;
- копировать можно только `StoredObject` текущего пользователя: если исходный `FileItem` ссылается на чужой `StoredObject`, возвращается `403 FORBIDDEN` до операций с файлами.

Response `200 OK`: новый `FileItemDto`.

Ошибки: `400` — некорректное имя, copy из обычной библиотеки в `CAMERA` или в `AUTO_UPLOADS`; `403 FORBIDDEN` — чужой `StoredObject`; `404` — нет исходного файла или target folder, в том числе источник удалён параллельно; `409 CONFLICT` — имя занято файлом или папкой, в том числе только на диске, либо зарезервировано, в `CAMERA` — тот же checksum; `409 FOLDER_MANAGED` — исходный файл или target folder в активном дереве источника; `500 INTERNAL_ERROR` — ошибка физического хранилища, в том числе отсутствие байтов источника при существующей записи.

### `DELETE /api/v1/files/{id}`

Удаляет файл текущего пользователя. В одной транзакции под блокировкой строки `ROOT` удаляются записи и файл переносится в `{storage.root}/.pending-delete/file-{id}`; физически его удаляет фоновая очистка. Имя свободно сразу после ответа.

Текущая логика:

- если текущий пользователь не владелец `StoredObject`, удаляется только его `FileItem`;
- если текущий пользователь владелец `StoredObject`, удаляются все `FileItem`, которые ссылаются на этот `StoredObject`, затем сам `StoredObject`, а файл переносится на удаление;
- `deletedAt` сейчас не используется как soft delete, удаление hard delete.

Response `204 No Content`. Если файла нет, в том числе после параллельного удаления: `404`. Файл занят: `409` без изменений. Файл в активном дереве источника: `409 FOLDER_MANAGED`.

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
| 404 | файл/папка не найдены или принадлежат другому пользователю; physical file отсутствует при download/thumbnail. JSON и при `Accept: image/*` |
| 409 | имя занято файлом или папкой, имя зарезервировано |
| 413 | multipart/request или streamed file превышает лимит |
| 415 | thumbnail: формат не поддерживается |
| 422 | thumbnail: изображение не декодируется или превышает пределы |
| 500 | `INTERNAL_ERROR`: ошибка физического хранилища (в том числе занятый физический путь); временная ошибка, запрос можно повторить. Физические конфликты намеренно не возвращают 409 |
| 503 | thumbnail: все слоты генерации заняты, `Retry-After: 1` |

## TODO

- sharing и корректная модель прав для чужих `FileItem`;
- replace/overwrite;
- auto-rename вида `file (1).jpg`;
- soft delete/trash на базе `deletedAt`;
- versioning;
- отдельный link-existing endpoint для создания `FileItem` без повторной загрузки байтов.
