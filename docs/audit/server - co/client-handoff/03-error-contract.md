# 03. Клиентский контракт ошибок

[CONFIRMED] Для ошибок, которые приложение явно обрабатывает:
~~~json
{"id":"00000000-0000-0000-0000-000000000000","code":"BAD_REQUEST","message":"Description","fieldErrors":null}
~~~
id — новый UUID ошибки. code — машинное имя enum. message может быть русский/английский; для business conflict произвольный service message, не устойчивый discriminator. fieldErrors при validation — map field→message, иначе null. Данные примера искусственные. Retryable ниже — [INFERRED] оценка по эффектам операции, сервер отдельного флага не возвращает.

| Operation | HTTP status | Error body | Meaning | Retryable |
| --- | --- | --- | --- | --- |
| [CONFIRMED] Любая protected |401 | ErrorResponse UNAUTHORIZED; Unauthorized: access token expired or invalid | Access отсутствует/невалиден/expired | После успешного refresh, учитывая повторяемость операции |
| [CONFIRMED] Public с bad Bearer |401 | UNAUTHORIZED | Header отклонён до login/refresh/reset body | Без ненужного bad access header; не бесконечно обновлять access |
| [CONFIRMED] Login |401 | INVALID_CREDENTIALS; Invalid email or password | Unknown/wrong/disabled/banned | Нет без исправления |
| [CONFIRMED] Register |409 | CONFLICT; Email is already registered | Email занят | Нет |
| [CONFIRMED] Refresh |401 | INVALID_REFRESH_TOKEN; Invalid refresh token | Unknown/invalid/expired/type/subject | Нет тем же refresh |
| [CONFIRMED] Refresh |403 | REFRESH_TOKEN_REVOKED; token revoked | Token revoked | Нет |
| [CONFIRMED] Logout/others |403 | REFRESH_TOKEN_OWNERSHIP_ERROR | Body token принадлежит другому account | Исправить account/token |
| [CONFIRMED] Logout/others |404 | REFRESH_TOKEN_NOT_FOUND | Token отсутствует | Обычно нет |
| [CONFIRMED] Security denial |403 | FORBIDDEN; Forbidden | Общее отсутствие права | Не без смены условий |
| [CONFIRMED] DTO validation |400 | VALIDATION_ERROR; Validation failed + fieldErrors | Null/blank/length/format | Исправить request |
| [CONFIRMED] JSON parse/missing body |400 | BAD_REQUEST; Malformed or missing request body | JSON отсутствует/нечитаем | Исправить request |
| [CONFIRMED] Required query |400 | BAD_REQUEST; Required request parameter is missing | Например code/email | Добавить параметр |
| [CONFIRMED] Register confirm/reset confirm |400 | BAD_REGISTRATION_REQUEST; обычно Запись не найдена | Code unknown/used/expired/wrong type | Нет тем же code |
| [CONFIRMED] Password reset request |400 | BAD_REQUEST; User <email> not found | Account отсутствует | Нет |
| [CONFIRMED] Files lookup/delete/rename/move/copy |404 | FILE_ITEM_NOT_FOUND; File item <id> not found | Missing либо чужой FileItem | Сверка account/id |
| [CONFIRMED] Download physical missing/unreadable |404 | FILE_ITEM_NOT_FOUND | Metadata может ещё существовать | Same-folder upload не repair; blind retry без изменения не поможет |
| [CONFIRMED] Folder operations / target / exists |404 | NOT_FOUND; Folder <id> not found | Missing/foreign folder | Уточнить target |
| [CONFIRMED] Folder illegal operation |400 | BAD_REQUEST + system/nonempty/cycle message | Запрещённое действие | Исправить operation |
| [CONFIRMED] File/folder names |409 | CONFLICT + service message | Имя занято/reserved | Сменить имя/target; CAMERA file name exception |
| [CONFIRMED] Copy checksum |409 | CONFLICT; File with this checksum already exists in folder | В папке уже есть content | Сверить target, не повторять blind |
| [CONFIRMED] Move checksum conflict |500 | DATABASE_CONSTRAINT_VIOLATION; Database constraint violation | При name-check passed, unique checksum нарушен | Нет с тем же target/content |
| [CONFIRMED] Other DB constraint failures |500 | DATABASE_CONSTRAINT_VIOLATION | Persistent integrity error/race | Причина неизвестна; reconcile до retry |
| [CONFIRMED] Upload empty |400 | BAD_REQUEST; Файл пустой | Нулевой MultipartFile | Нет теми же bytes |
| [CONFIRMED] Stream file size |413 | FILE_TOO_LARGE; File size exceeds limit: <bytes> bytes | Max service100MiB | Уменьшить file |
| [CONFIRMED] Servlet multipart size |413 | FILE_TOO_LARGE; Multipart request exceeds configured limit | Max file/request110MB | Уменьшить request |
| [CONFIRMED] Checksum batch size |400 | BAD_REQUEST; Checksum batch size must be at most <max> | Raw count>500 | Разбить batch |
| [CONFIRMED] Negative page / size<=0 |400 | BAD_REQUEST + exception message | PageRequest rejected | Исправить query |
| [CONFIRMED]7 reserved operations |501 | {message: "... is not implemented yet"} | Feature stub | Нет |
| [INFERRED] IO/download/copy/runtime/framework | Обычно4xx/5xx, точная ветвь не закреплена | Не гарантирован ErrorResponse | Нет project-specific общего handler | По типу операции; результат write может быть неизвестен |
| [INFERRED] Network disconnect/timeout | Ответ может отсутствовать | Body отсутствует/неполный | Commit мог произойти | Upload same bytes+folder можно повторить; copy/create требуют reconciliation |

[CONFIRMED] Особые случаи без error response: metadata extractor error → успешный upload с optional metadata; ошибка физического delete после committed DB →204 и server log; failed cleanup не меняет исходный status.

[CONFIRMED] Повтор upload duplicate →200 существующий DTO, **не409**. Copy duplicate →409. Повтор delete после успешного hard delete →404, **не204**. Пустой список exists запрещён400; пустая страница list нормальна200.

[CONFIRMED] Для type mismatch path/query, missing multipart part, unsupported media type, unsupported method и необработанного IO точная форма framework response не задана custom advice. INTERNAL_ERROR enum объявлен, но сервер не использует его как catch-all. Поэтому клиентский parser должен уметь обрабатывать отсутствие ожидаемой JSON-схемы; это вывод из текущего покрытия handlers, не новая API-функция.

Источники: [GlobalExceptionHandler](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java), [security handlers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java).
