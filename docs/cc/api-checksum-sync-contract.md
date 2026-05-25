# Checksum Exists API contract

Документ описывает текущий checksum pre-check endpoint. Это не полноценная синхронизация, а быстрый ответ клиенту перед upload: какие SHA-256 уже присутствуют в физическом хранилище пользователя, а какие нужно загрузить.

Endpoint требует `Authorization: Bearer <accessToken>`.

## Endpoint

### `POST /api/v1/files/checksums/exists`

Request:

```json
{
  "checksums": [
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  ]
}
```

Validation:

| Field | Правила |
| --- | --- |
| `checksums` | not null, not empty |
| каждый элемент | not blank, regexp `^[0-9a-fA-F]{64}$` |
| размер batch | не более `sync.checksum-exists.max-batch-size` (текущее значение: `500`) |

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

- Checksum — SHA-256, 64 hex-символа.
- Входные checksum нормализуются в lowercase перед проверкой.
- Дубликаты во входном списке схлопываются через `LinkedHashSet`; каждый уникальный checksum встречается в ответе ровно один раз.
- Порядок в ответе соответствует порядку первого появления checksum в запросе.
- Файл считается **существующим**, если в БД есть `StoredObject` с `user_id = текущий пользователь` и `checksum = переданное значение`.
- `FileItem` и `Folder` намеренно не учитываются.
- Response намеренно минимальный: только `existing` и `missing`.

Пример с дубликатами и смешанным регистром:

```json
{
  "checksums": [
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  ]
}
```

Response: checksum один раз, в lowercase:

```json
{
  "existing": [
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  ],
  "missing": []
}
```

## Назначение для Android-клиента

Клиент формирует batch из SHA-256 файлов, которые собирается загрузить. Сервер отвечает, какие уже есть в `StoredObject`. Для файлов из `existing` клиент может пропустить загрузку байтов.

**Важно:** текущий сервер при этом не создает новый `FileItem`. Клиент узнает о факте наличия физического объекта, но логическая запись не создается автоматически.

## Ограничения текущей реализации

Этот endpoint отвечает только на вопрос «есть ли физический объект у пользователя». Он **не** сообщает:

- в какой папке лежит файл;
- есть ли логическая запись `FileItem`;
- `fileType`, размер, дату съемки или имя файла;
- можно ли создать логическую ссылку без upload.

## Ошибки

| Status | Когда |
| --- | --- |
| 400 | пустой список, blank checksum, checksum не 64 hex-символа, batch превышает лимит |
| 401 | нет или невалидный access token |

Пример validation error:

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

Превышение `max-batch-size` бросает `IllegalArgumentException`; ответ — `400 BAD_REQUEST`.

## TODO

- `link-existing`: отдельный endpoint для создания `FileItem` на существующий `StoredObject` без повторной загрузки байтов.
- `Device` model: хранить устройство, источник и состояние синхронизации.
- Full sync sessions: полноценные сессии сверки локального индекса клиента с сервером.
- Extended response: расширенный endpoint с `fileType`, `size`, `capturedAt` и другими metadata, не усложняя текущий минимальный pre-check.
