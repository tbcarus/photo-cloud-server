# Folder API contract

Документ описывает текущий контракт папок. Базовый путь: `/api/v1/folders`. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`.

## Модель

`Folder` хранит логическую структуру пользователя в БД:

| Field | Тип | Описание |
| --- | --- | --- |
| `id` | Long | первичный ключ |
| `user` | User | владелец |
| `parent` | Folder | родительская папка; `null` только у ROOT |
| `name` | String (max 255) | имя папки |
| `folderType` | FolderType | `ROOT`, `CAMERA`, `FILES`, `USER` |
| `createdAt` | LocalDateTime | дата создания, не обновляется |
| `updatedAt` | LocalDateTime | дата последнего обновления |

`children` не хранятся коллекцией в entity. Дочерние папки запрашиваются через repository по `userId + parentId`.

Response DTO:

```json
{
  "id": 10,
  "parentId": 1,
  "name": "Trips",
  "folderType": "USER",
  "createdAt": "2026-05-17T10:15:30",
  "updatedAt": "2026-05-17T10:15:30"
}
```

У ROOT `parentId` равен `null`.

## Типы папок

| Type | Назначение |
| --- | --- |
| `ROOT` | корневая папка пользователя; ровно одна на пользователя |
| `CAMERA` | системная дочерняя папка ROOT; default target для `IMAGE` и `VIDEO` при upload без `folderId` |
| `FILES` | системная дочерняя папка ROOT; default target для `AUDIO`, `DOCUMENT`, `ARCHIVE`, `OTHER` |
| `USER` | пользовательские папки |

## Инварианты

- У пользователя ровно один `ROOT`; создается лениво при первом обращении через `GET /folders/root`.
- `CAMERA` и `FILES` создаются лениво как дочерние папки `ROOT` при первом upload без `folderId`.
- `USER`-папки можно создавать в `ROOT` и внутри других `USER`-папок.
- Внутри `CAMERA` и `FILES` создавать папки нельзя (ни через create, ни через move).
- `ROOT`, `CAMERA`, `FILES` нельзя переименовывать, перемещать или удалять.
- Имена `Camera` и `Files` зарезервированы внутри `ROOT` (нельзя создать USER-папку с таким именем).
- Имена папок сравниваются без учета регистра: в одном parent не может быть двух папок с одинаковым именем в любом регистре.
- Move проверяет цикл: папка не может быть перемещена в саму себя или в своего потомка.
- Delete разрешен только для пустой `USER`-папки: без дочерних папок и без файлов (`FileItem`).

TODO: recursive delete должен учитывать sharing, права доступа, каскадное удаление `FileItem` и физических `StoredObject`.

## Endpoints

### `GET /api/v1/folders/root`

Возвращает `ROOT` текущего пользователя. Если ROOT еще не существует — создает его.

Response `200 OK`:

```json
{
  "id": 1,
  "parentId": null,
  "name": "root",
  "folderType": "ROOT",
  "createdAt": "2026-05-17T10:15:30",
  "updatedAt": "2026-05-17T10:15:30"
}
```

### `GET /api/v1/folders/{id}/children`

Возвращает только прямых потомков указанной папки. Рекурсивное дерево не возвращается.

Response `200 OK`:

```json
[
  {
    "id": 2,
    "parentId": 1,
    "name": "Camera",
    "folderType": "CAMERA",
    "createdAt": "2026-05-17T10:15:30",
    "updatedAt": "2026-05-17T10:15:30"
  },
  {
    "id": 3,
    "parentId": 1,
    "name": "Files",
    "folderType": "FILES",
    "createdAt": "2026-05-17T10:15:30",
    "updatedAt": "2026-05-17T10:15:30"
  }
]
```

Ошибки:

| Status | Когда |
| --- | --- |
| 404 | папка не найдена или принадлежит другому пользователю |

### `POST /api/v1/folders`

Создает `USER`-папку. Если `parentId` не передан — parent будет текущий `ROOT`.

Request:

```json
{
  "parentId": 1,
  "name": "Documents"
}
```

Validation:

| Field | Правила |
| --- | --- |
| `parentId` | optional; если null — используется ROOT |
| `name` | not blank, max 255 символов; service дополнительно trim |

Response `201 Created`: `FolderDto` созданной папки.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | blank или слишком длинное имя, попытка создать папку внутри `CAMERA`/`FILES` |
| 404 | parent не найден или не принадлежит пользователю |
| 409 | имя занято в parent (case-insensitive) или зарезервировано в ROOT |

### `PATCH /api/v1/folders/{id}`

Переименовывает только `USER`-папку.

Request:

```json
{
  "name": "Archive"
}
```

Validation: `name` — not blank, max 255.

Response `200 OK`: обновленный `FolderDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | попытка переименовать системную папку (`ROOT`/`CAMERA`/`FILES`), blank или слишком длинное имя |
| 404 | папка не найдена или принадлежит другому пользователю |
| 409 | имя занято в parent (case-insensitive) или зарезервировано в ROOT |

### `POST /api/v1/folders/{id}/move`

Перемещает только `USER`-папку. Target parent может быть `ROOT` или `USER`-папкой.

Request:

```json
{
  "targetParentId": 12
}
```

Validation: `targetParentId` — not null.

Response `200 OK`: обновленный `FolderDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | попытка переместить системную папку, target равен самой папке, target является потомком переносимой папки, target — `CAMERA` или `FILES` |
| 404 | папка или target parent не найдены или принадлежат другому пользователю |
| 409 | имя папки уже занято в target parent (case-insensitive) или зарезервировано в ROOT |

### `DELETE /api/v1/folders/{id}`

Удаляет только пустую `USER`-папку.

Response `204 No Content`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | попытка удалить системную папку, папка содержит child folders или files |
| 404 | папка не найдена или принадлежит другому пользователю |

## Security

Все операции привязаны к текущему `@AuthenticationPrincipal User`. Repository lookup идет по `id + userId`, поэтому доступ к чужой папке возвращает `404`, а не `403` (no information leakage).
