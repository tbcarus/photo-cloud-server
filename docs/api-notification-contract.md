# Notification API contract

Документ описывает контракт уведомлений текущего пользователя. Все endpoint-ы требуют `Authorization: Bearer <accessToken>`, без него — `401`. Поведение и правила — System SPEC [NOTIFICATIONS-READ](../../system-spec/docs/spec/system/06-sharing-and-access.md#notifications-read), модель — [Notification](../../system-spec/docs/spec/system/01-system-model.md#notification).

Уведомления создаёт только Server: сейчас — `SHARE_GRANTED` при создании нового USER-доступа (`POST /api/v1/shares/users`, `api-share-contract.md`). Push и email нет.

## DTO

`NotificationDto`:

```json
{
  "id": 15,
  "type": "SHARE_GRANTED",
  "title": "Предоставлен доступ",
  "body": "Вам предоставлен доступ к папке „Trips“",
  "targetType": "FOLDER",
  "targetId": 12,
  "createdAt": "2026-11-08T10:15:30.123456Z",
  "readAt": null
}
```

`type`: `SHARE_GRANTED` | `BACKUP_FAILED` | `SYSTEM_MESSAGE` (последние два зарезервированы и сейчас не создаются; клиент должен терпеть неизвестные значения). `targetType`: `FILE` | `FOLDER` | `null`; у `SHARE_GRANTED` `targetType` и `targetId` заданы всегда. `readAt` — `null`, пока не прочитано. Даты — UTC.

`title` и `body` — текст на момент события, не меняются при переименовании или удалении объекта. `targetType`/`targetId` — указатель для навигации, а не право: объект может быть уже удалён или недоступен, тогда его read-endpoint-ы отвечают `404`.

## Endpoints

### `GET /api/v1/notifications?page=0&size=10`

`PageResponse<NotificationDto>` (`items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`) — уведомления текущего пользователя, от новых к старым (`createdAt` по убыванию, при равенстве — `id` по убыванию). `page` по умолчанию `0`, `size` — `10`; верхний предел `size` не задан. Нет уведомлений — пустая страница. Отрицательный `page` или `size` ≤ 0 — `400 BAD_REQUEST`.

### `GET /api/v1/notifications/unread-count`

```json
{ "count": 3 }
```

Число уведомлений текущего пользователя с `readAt = null`.

### `PATCH /api/v1/notifications/{id}/read`

Без тела. Устанавливает `readAt` текущим временем Server; уже прочитанное сохраняет исходный `readAt`, повтор не меняет данные. Response `200 OK`: `NotificationDto`.

| Status | Code | Когда |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | `id` не число |
| 404 | `NOT_FOUND` | уведомление отсутствует или принадлежит другому пользователю |

### `PATCH /api/v1/notifications/read-all`

Без тела. Отмечает прочитанными все непрочитанные уведомления текущего пользователя; `readAt` прочитанных не меняется. Response `204 No Content`, в том числе если отмечать нечего.
