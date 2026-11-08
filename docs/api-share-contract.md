# Share API contract

Документ описывает контракт USER-доступов. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`. Поведение и правила — System SPEC [SHARING-USER-MANAGE](../../system-spec/docs/spec/system/06-sharing-and-access.md#sharing-user-manage), [SHARED-WITH-ME](../../system-spec/docs/spec/system/05-file-library.md#shared-with-me), чтение по доступу — [ACCESS-READ](../../system-spec/docs/spec/system/06-sharing-and-access.md#access-read).

Получатель только читает: children и список файлов папки, metadata, download и thumbnail (`api-folder-contract.md`, `api-file-contract.md`). Операции изменения чужих объектов дают `404`.

Срок `expiresAt` — момент времени ISO-8601 со смещением (`2031-05-01T12:00:00+03:00`), в ответе — UTC (`2031-05-01T09:00:00Z`); точность — микросекунды. `null` — бессрочно. Доступ действует, пока текущее время строго меньше `expiresAt`.

## DTO

`ShareDto` — выданный доступ глазами владельца:

```json
{
  "id": 7,
  "resourceType": "FOLDER",
  "resourceId": 12,
  "resourceName": "Trips",
  "recipient": { "id": 3, "email": "alice@example.com", "displayName": null },
  "expiresAt": "2031-05-01T09:00:00Z",
  "status": "ACTIVE",
  "createdAt": "2026-11-08T10:15:30.123456Z"
}
```

`resourceType`: `FILE` | `FOLDER`. `status`: `ACTIVE` | `EXPIRED`, вычисляется в момент ответа. Поля LINK-доступа не выдаются.

`SharedResourceDto` — элемент «Доступно мне»:

```json
{
  "shareId": 7,
  "resourceType": "FILE",
  "resourceId": 41,
  "name": "a.jpg",
  "file": { "id": 41, "folderId": 12, "originalFilename": "a.jpg", "mimeType": "image/jpeg", "size": 1024, "checksum": "...", "fileType": "IMAGE", "...": "остальные поля FileItemDto" },
  "owner": { "id": 2, "email": "owner@example.com", "displayName": null },
  "expiresAt": null,
  "sharedAt": "2026-11-08T10:15:30.123456Z"
}
```

`file` — только у `FILE`, у `FOLDER` отсутствует. `backupSource` папки не выдаётся.

Списки возвращают `PageResponse` (`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`); `page` по умолчанию `0`, `size` — `10`; порядок — от новых доступов к старым.

## Endpoints владельца

### `POST /api/v1/shares/users`

```json
{ "folderId": 12, "emails": ["alice@example.com", "bob@example.com"], "expiresAt": null }
```

Ровно один из `fileId`, `folderId`. Адреса сравниваются без учёта регистра; адреса одного аккаунта — один получатель. Операция атомарна: при любом недопустимом получателе не создаётся и не меняется ни один доступ. Повторная выдача тому же получателю обновляет `expiresAt` существующей записи (в том числе продлевает истёкшую). Выполняется под блокировкой дерева владельца.

Response `200 OK`: `ShareDto[]` — по одному на получателя в порядке первого упоминания.

| Status | Code | Когда |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | пустой `emails`, пустой или некорректный адрес |
| 400 | `BAD_REQUEST` | не указан ресурс или указаны оба; `expiresAt` не в будущем; ресурс `ROOT` или `AUTO_UPLOADS` |
| 400 | `SHARE_RECIPIENT_UNAVAILABLE` | недопустимые получатели; `fieldErrors`: адрес → `Recipient is not available` (нет аккаунта, не активирован, заблокирован) или `Cannot share with yourself` |
| 404 | `FILE_ITEM_NOT_FOUND` / `NOT_FOUND` | файл или папка отсутствуют либо принадлежат другому пользователю |

### `GET /api/v1/shares?fileId=&folderId=&page=0&size=10`

`PageResponse<ShareDto>` — USER-доступы, выданные текущим пользователем, включая истёкшие. Фильтр — не более одного из `fileId`, `folderId`; ресурс должен быть собственным, иначе `404`. Оба фильтра — `400`.

### `PATCH /api/v1/shares/{id}`

`{"expiresAt": "2031-01-01T00:00:00Z"}` или `{"expiresAt": null}` — снять срок. Тело без поля `expiresAt` — `400`; срок не в будущем — `400`. Response `200 OK`: `ShareDto`. Доступ другого владельца или отсутствующий — `404 NOT_FOUND`.

### `DELETE /api/v1/shares/{id}`

Отзыв: запись удаляется физически. `204 No Content`; отсутствующий или чужой доступ, повтор — `404 NOT_FOUND`.

### `DELETE /api/v1/shares/users?fileId=...` | `?folderId=...`

Отзыв всех USER-доступов к собственному ресурсу. `204 No Content`, в том числе если доступов не было. Ровно один параметр, иначе `400`; чужой или отсутствующий ресурс — `404`.

## Endpoints получателя

### `GET /api/v1/shared-with-me?page=0&size=10`

`PageResponse<SharedResourceDto>` — только прямые действующие USER-доступы текущего пользователя: срок не истёк, владелец активен и не заблокирован, ресурс не `ROOT`/`AUTO_UPLOADS`. Объекты внутри предоставленной папки отдельными элементами не возвращаются.

### `DELETE /api/v1/shared-with-me/{shareId}`

Отказ от своего доступа: удаляет Share, ресурс и остальные доступы не меняются. `204 No Content`; отсутствующий доступ или доступ другого получателя — `404 NOT_FOUND`.
