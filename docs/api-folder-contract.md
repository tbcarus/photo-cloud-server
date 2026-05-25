# Folder API contract

Документ описывает текущий контракт папок. Базовый путь: `/api/v1/folders`. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`.

## Модель

`Folder` хранит логическую структуру пользователя в БД:

| Field | Описание |
| --- | --- |
| `id` | id папки |
| `user` | владелец |
| `parent` | родительская папка, `null` только у ROOT |
| `name` | имя до 255 символов |
| `folderType` | `ROOT`, `CAMERA`, `FILES`, `USER` |
| `createdAt` | дата создания |
| `updatedAt` | дата обновления |

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

`children` не хранятся отдельной коллекцией в entity-контракте. Дочерние папки запрашиваются через repository по `userId + parentId`.

## Типы папок

| Type | Назначение |
| --- | --- |
| `ROOT` | корневая папка пользователя |
| `CAMERA` | системная дочерняя папка ROOT для `IMAGE` и `VIDEO` при upload без `folderId` |
| `FILES` | системная дочерняя папка ROOT для остальных типов файлов при upload без `folderId` |
| `USER` | пользовательские папки |

## Инварианты

- У пользователя ровно один `ROOT`; он создается лениво при первом обращении.
- `CAMERA` и `FILES` создаются лениво как дочерние папки `ROOT`.
- `USER`-папки могут быть вложенными в `ROOT` или другие `USER`-папки.
- `ROOT`, `CAMERA`, `FILES` нельзя переименовывать, перемещать или удалять.
- Внутри `CAMERA` и `FILES` нельзя создавать или перемещать папки.
- Имена `Camera` и `Files` зарезервированы внутри `ROOT`.
- В одном parent не может быть двух папок с одинаковым именем без учета регистра.
- Move проверяет, что папка не перемещается в саму себя или в своего потомка.
- Delete на текущем этапе разрешен только для пустых `USER`-папок: без дочерних папок и без файлов.

TODO: будущий recursive delete должен учитывать sharing, права доступа, удаление `FileItem` и физическое удаление `StoredObject`.

## Endpoints

### `GET /api/v1/folders/root`

Возвращает `ROOT` текущего пользователя. Если ROOT еще нет, создает его.

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

Возвращает только прямых потомков папки. Рекурсивное дерево не возвращается.

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
  }
]
```

Если папка не найдена или принадлежит другому пользователю: `404`.

### `POST /api/v1/folders`

Создает `USER`-папку. Если `parentId=null`, parent будет текущий `ROOT`.

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
| `parentId` | optional |
| `name` | not blank, max 255; дополнительно trim в service |

Response `200 OK`: `FolderDto` созданной папки.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | blank name, имя длиннее 255 после normalize, попытка создать папку внутри `CAMERA`/`FILES` |
| 404 | parent не найден или не принадлежит пользователю |
| 409 | имя занято в parent или зарезервировано в ROOT |

### `PATCH /api/v1/folders/{id}`

Переименовывает только `USER`-папку.

Request:

```json
{
  "name": "Archive"
}
```

Response `200 OK`: обновленный `FolderDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | попытка переименовать системную папку, blank/слишком длинное имя |
| 404 | папка не найдена или чужая |
| 409 | имя занято в parent или зарезервировано в ROOT |

### `POST /api/v1/folders/{id}/move`

Перемещает только `USER`-папку в `ROOT` или другую `USER`-папку.

Request:

```json
{
  "targetParentId": 12
}
```

Response `200 OK`: обновленный `FolderDto`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | move системной папки, target равен самой папке, target находится внутри переносимой папки, target=`CAMERA`/`FILES` |
| 404 | папка или target parent не найдены или чужие |
| 409 | имя папки уже занято в target parent или зарезервировано в ROOT |

### `DELETE /api/v1/folders/{id}`

Удаляет только пустую `USER`-папку.

Response `204 No Content`.

Ошибки:

| Status | Когда |
| --- | --- |
| 400 | попытка удалить системную папку, папка содержит child folders или files |
| 404 | папка не найдена или чужая |

## Security

Все операции используют текущего `@AuthenticationPrincipal User`. Доступ к чужой папке не раскрывается отдельным `403`; repository lookup идет по `id + userId`, поэтому клиент получает `404`.
