# 06. Возможности сервера

[CONFIRMED] Срез по исходникам 2026-09-10. «Да» означает реализованный код, не runtime certification. Все пути ниже под /api/v1; детали request/response в01 пакета.

| Capability | Supported | Endpoint/mechanism | Notes |
| --- | --- | --- | --- |
| Login | Да | POST /auth/login | Access20min + refresh7days |
| Register/confirm email | Да | POST /auth/register; GET /auth/register/confirm | Confirmation code3days |
| Refresh | Да | POST /auth/refresh-token | Без rotation, только access |
| Logout/current/all/others | Да | POST /auth/logout, /logout-all, /logout-others | Refresh revocation; access остаётся |
| Multiple logins | Да | Новый refresh каждый login | Не device-aware |
| Device registration/list sessions | Нет | Не найден | Нет deviceId/session list API |
| Password reset JSON | Да | POST /auth/password/reset/request и /confirm | Не отзывает existing tokens |
| Browser reset flow | Частично | GET/POST /auth/password/reset/page | Page501; ссылка письма ведёт сюда |
| Resend emails | Нет, stub | register/resend; password/reset/resend |501 |
| Read profile | Да | GET /profile | UserDto |
| Edit profile/settings | Нет, stub | PATCH /profile; GET/PATCH /profile/settings |501 |
| User deletion | Нет | Не найден | Нет client-facing account delete |
| Upload | Да | POST /files; POST /files/upload | Один multipart file, optional folder,100MiB |
| Existence check | Да | POST /files/checksums/exists | Folder-scoped SHA256, max500 |
| Legacy checksum list | Да | GET /files/checksums | Без folderId/pagination |
| List files | Да | GET /files | Page0/size10, optional folderId, fixed sort |
| Metadata card | Да | GET /files/{id} | Нет physical path |
| Download | Да | GET /files/{id}/download | Raw bytes; missing bytes404 |
| File rename | Да | PATCH /files/{id} | Request originalName |
| File move | Да | POST /files/{id}/move | Checksum conflict может дать500 |
| File copy | Да | POST /files/{id}/copy | New physical bytes; duplicate checksum409 |
| File delete | Да | DELETE /files/{id} | Hard delete204 |
| ROOT | Да | GET /folders/root | Lazy create |
| Folder children | Да | GET /folders/{id}/children | Только direct, no pagination |
| USER folders create/rename/move | Да | POST /folders; PATCH /folders/{id}; POST /folders/{id}/move | System folders immutable |
| Empty USER folder delete | Да | DELETE /folders/{id} | Nonempty400 |
| Recursive folder delete | Нет | Не найден | TODO only |
| Albums | Нет | Не найден | Folder не отдельная Album model |
| Thumbnails/previews | Нет | Не найден | Legacy thumbnail_path dropped |
| Image metadata | Частично | Automatic extractor | EXIF/GPS, JPEG/PNG dimensions, nullable |
| Video upload/download | Да | Общий files API | MIME VIDEO → default CAMERA |
| Video duration/transcoding | Нет обработки | durationSec field только | Current extractor image-only |
| Audio/docs/archives/other | Да | Общий upload | OTHER не запрещён |
| Dedup in folder | Да | user+folder+checksum | Upload duplicate200; copy409 |
| Physical dedup between folders/users | Нет | New StoredObject per upload/copy | Независимые bytes |
| Link-existing/share | Нет API | Model references допускают | Не путать с copy по source FileItem ID |
| Resumable/chunked upload | Нет | Не найден | Network retry целиком |
| Upload status/complete callback | Нет | Не найден | Только HTTP200 DTO |
| Full sync/delta/change feed | Нет | Не найден | Нет tombstones/cursors |
| Client metadata override | Нет | Upload DTO отсутствует | Server derives bytes metadata |
| Search/type/date filters | Нет | Не объявлены controller | page,size,folderId only |
| Overwrite/versioning/trash | Нет | deletedAt unused | Не реализованные mutations |
| Quota remaining / storage full status | Нет | Не найден | Только per-file413 |
| CORS origin policy | Не задана | Нет explicit config | Native Android HTTP не browser CORS |
| Health/readiness | Частично | GET /test, /test/auth | Connectivity ping, не полный health |

Источники: [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [services](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [model](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model).
