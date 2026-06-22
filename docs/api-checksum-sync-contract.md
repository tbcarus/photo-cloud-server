# Checksum Exists API contract

Документ описывает текущий checksum pre-check endpoint. Это не полноценная синхронизация, а быстрый ответ клиенту перед upload: какие SHA-256 уже есть **в указанной папке** пользователя, а какие нужно загружать.

Endpoint требует `Authorization: Bearer <accessToken>`.

## Endpoint

### `POST /api/v1/files/checksums/exists`

Request:

```json
{
  "folderId": 123,
  "checksums": [
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  ]
}
```

Validation:

| Field | Правила |
| --- | --- |
| `folderId` | not null; папка должна принадлежать текущему пользователю |
| `checksums` | not empty |
| item | not blank, regexp `^[0-9a-fA-F]{64}$` |
| batch size | не больше `sync.checksum-exists.max-batch-size`, сейчас `500` |

Response `200 OK`:

```json
{
  "existing": [
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  ],
  "missing": [
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  ]
}
```

## Семантика

Критерий существования — **user + folder + checksum**. Endpoint проверяет **логическое наличие файла в папке** (`FileItem`), а не физический слой `StoredObject`.

- Checksum считается SHA-256 в hex, 64 символа.
- Входные checksum нормализуются в lowercase.
- Дубликаты во входном списке схлопываются через `LinkedHashSet`.
- Порядок ответа следует порядку первого появления checksum в request.
- `existing` означает: в папке `folderId` текущего пользователя уже есть `FileItem` с таким checksum.
- `missing` означает: в этой папке такого `FileItem` нет.
- Тот же checksum в **другой папке** пользователя на результат не влияет (это не дубль для текущей папки).
- Тот же checksum у **другого пользователя** на результат не влияет.

Пример с дубликатами:

```json
{
  "folderId": 123,
  "checksums": [
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  ]
}
```

Response содержит checksum один раз и в lowercase:

```json
{
  "existing": [
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  ],
  "missing": []
}
```

## Ограничения текущей реализации

Этот endpoint отвечает только на вопрос "есть ли логический файл с таким checksum в этой папке". Он не говорит:

- есть ли такой checksum в других папках пользователя;
- какой `fileType`, размер, дата съемки или имя у файла;
- можно ли создать логическую ссылку без upload.

Для Android-клиента это pre-check перед upload в конкретную папку: клиент может не загружать байты, если checksum попал в `existing` для этой папки. Сервер не создает новый `FileItem` через этот endpoint.

## Ошибки

| Status | Когда |
| --- | --- |
| 400 | пустой список, blank checksum, checksum не 64 hex chars, batch больше лимита, отсутствует `folderId` |
| 401 | нет/невалидный access token |
| 404 | `folderId` не существует или принадлежит другому пользователю |

Формат validation error общий:

```json
{
  "id": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": {
    "checksums[0]": "must match \"^[0-9a-fA-F]{64}$\""
  }
}
```

Если batch превышает config-limit, сервис бросает `IllegalArgumentException`; ответ будет `400 BAD_REQUEST`.

## TODO

- `link-existing`: отдельный endpoint для создания `FileItem` на существующий `StoredObject` без повторной загрузки байтов.
- `Device` model: хранить устройство, источник и состояние синхронизации.
- Full sync sessions: полноценные сессии сверки локального индекса клиента с сервером.
- Extended response: отдельный расширенный endpoint с `fileType`, `size`, `capturedAt` и другими metadata, не усложняя текущий минимальный pre-check.
