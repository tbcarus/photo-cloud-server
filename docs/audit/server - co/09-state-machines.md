# 09. Состояния и переходы

[CONFIRMED] Формальной библиотеки state machine, таблицы upload state и enum processing/media status нет. Ниже явные поля и аналитическая реконструкция стадий; названия стадий upload в схеме — [INFERRED], они не значения server/client DTO.

Источники: [entities](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [JwtService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java), [EmailRequestService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java), [FolderService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java).

## Пользователь

| Состояние/переход | Инициатор/условие | Итог/ошибка |
| --- | --- | --- |
| [CONFIRMED] Absent → enabled=false,banned=false | register валидного нового email/password | USER role, hash, activation code |
| [CONFIRMED] disabled → enabled=true | Valid unused ACTIVATE younger3days | user enabled; code used |
| [CONFIRMED] enabled=true,banned=false → login | Правильный password | tokens + lastLoginAt; flags прежние |
| [CONFIRMED] disabled либо banned → login | Любой password после lookup |401 INVALID_CREDENTIALS |
| [CONFIRMED] password old → new | Valid reset code | новый hash; JWT состояния прежние |
| [CONFIRMED] ban/unban/disable/delete | HTTP переход не найден | Не считать реализованной lifecycle-командой |

[RISK] Flags не повторно проверяются access/refresh; state «banned» не является реальной полной границей доступа для уже выданных токенов. Terminal state пользователя в API не определён. Прямые DB edits не являются приложением предусмотренными переходами.

## EmailRequest

[CONFIRMED] Поля type, used, createdAt. Начальное used=false; type задаётся один раз. Expired — computed createdAt+3days<=now; отдельной сохранённой даты expiry нет. isActive() проверяет только время, поэтому использованный свежий code имеет isActive=true, но service его отвергнет.

~~~mermaid
stateDiagram-v2
  [*] --> Fresh: generateEmailRequest
  Fresh --> Used: confirm valid type
  Fresh --> Expired: time reaches createdAt + 3 days
  Used --> Used: repeat rejected 400
  Expired --> Expired: request rejected 400
~~~

[CONFIRMED] ACTIVATE использует текущий code; PASSWORD_RESET использует current и все reset rows в окне3days. Wrong type/unknown/used/expired всегда400 BAD_REGISTRATION_REQUEST NOT_FOUND. Used/expired терминальны для использования, но строки не удаляются. delete(emailRequest) — пустой метод. Переотправка501, auto recovery нет.

[RISK] Код одноразовый по проверке флага, но нет @Version/lock/conditional update. Два параллельных confirm могут прочитать used=false до commit. Поэтому формальная гарантия exactly-once не подтверждена.

## RefreshToken

[CONFIRMED] Состояние — наличие строки + revoked flag + JWT exp. expires БД — копия, не источник текущей проверки. Начальное revoked=false; отдельного processing state нет.

| Transition | Условие/инициатор | Persisted fields | Ошибка |
| --- | --- | --- | --- |
| [CONFIRMED] Absent→valid | successful login | INSERT full token/userName/expires/revoked=false | DB/runtime failure |
| [CONFIRMED] valid→valid | refresh | Ничего не меняется; новый access |401 invalid JWT/user |
| [CONFIRMED] valid→revoked | logout own | revoked=true, revokedAt=now |404 unknown,403 foreign |
| [CONFIRMED] valid→revoked | logout-all/others | revoked=true; revokedAt прежний/null | Нет общего single transaction с concurrent login |
| [CONFIRMED] valid→expired | Прохождение времени exp | DB flags без изменений |401 INVALID_REFRESH_TOKEN |
| [CONFIRMED] revoked→revoked | refresh | Без изменений |403 REFRESH_TOKEN_REVOKED |
| [CONFIRMED] revoked→revoked | Повтор own logout | revokedAt обновляется |200 |

Нет rotation/revive/delete transition; terminal для refresh — expired или revoked. Их rows хранятся бессрочно по текущему коду. Если token одновременно expired+revoked, revoked branch раньше signature/expiry, после user lookup.

## Access token

[CONFIRMED] Stateless JWT: issued → valid until exp → expired. Сервер не хранит state и не делает revoked по logout/reset. Principal загружается заново; роли берутся из DB. Смена signing key может сделать старые JWT invalid, но key rotation flow не описан кодом. Удаление user не имеет аккуратно обработанного token-state transition.

## Upload / physical consistency

[INFERRED] Стадии реконструированы по local variables tempFile/finalFile/movedToFinal и DB commit:

~~~mermaid
stateDiagram-v2
  [*] --> ReceivingTemp
  ReceivingTemp --> Analyzed: full stream + hash + MIME
  Analyzed --> Existing: same user-folder-checksum
  Existing --> [*]: temp cleanup and 200 old DTO
  Analyzed --> FinalWithoutDB: name allowed + move
  FinalWithoutDB --> Committed: DB transaction commits
  Committed --> [*]: 200 new DTO
  ReceivingTemp --> Failed: IO or size limit
  Analyzed --> Failed: invalid folder or name conflict
  FinalWithoutDB --> Failed: DB error
  Failed --> [*]: best-effort cleanup
~~~

[CONFIRMED] Эти стадии не доступны клиенту. Client не может опросить progress/complete по uploadId. Авария процесса может оставить temp, final без DB либо DB без доступных bytes. Нет durable state/recovery scheduler. Потеря сети не переводит сохранённый FileItem в failed.

## FileItem / StoredObject / metadata

[CONFIRMED] Обычный state: сохранённая FileItem с required relations, optional metadata; deletedAt=null. Rename и move не меняют media status; type остаётся прежним. Copy порождает новую пару, не version. Delete terminal — hard deletion DB row. Нет SOFT_DELETED/RESTORED transition, deletedAt не заполняется и не фильтруется.

[INFERRED] Состояния согласованности: (row=1,bytes=1) нормальное; (row=1,bytes=0) missing physical; (row=0,bytes=1) orphan; (row=0,bytes=0) отсутствует. API exposes404 download для второго, но list/checksum могут считать файл существующим. Нет восстановления второго повторным same-folder upload.

[CONFIRMED] Metadata absent допустима и не означает FAILED: extractor best effort; row не создаётся без metadata fields. durationSec не заполняется. StoredObject не имеет deleted/status/refcount поля; references ищутся запросом.

## Folder

[CONFIRMED] FolderType — категория, не processing state. Initial ROOT/системные children lazy; USER — explicit create. Тип через API неизменяем. Rename/move USER при валидном имени/parent; delete только empty USER. Ошибки:400 invalid/system/cycle/nonempty;404 ownership/missing;409 name/reserved. Удаление terminal без корзины. Recover/restore/recursive delete отсутствуют.

[DOCUMENTED] Статусы НОВЫЙ/НА ПРОВЕРКЕ/ЗАГРУЖАЕТСЯ/ЛИМИТ из README-description относятся к идеям клиентской БД. Они не являются частью server state machine.
