# Фактическая модель состояния

Формальной state machine и enum upload/processing/sync status нет. Состояние определяется DB fields, наличием строк и физических bytes. Названия строк таблиц ниже — описания комбинаций, не значения API.

## User и email-коды

| Условие / событие | Текущее состояние / эффект |
| --- | --- |
| Register | enabled=false, banned=false, USER; ACTIVATE code used=false |
| Valid ACTIVATE | enabled=true; used=true текущего code |
| Login enabled и не banned | lastLoginAt обновляется; создаётся refresh |
| Login disabled/banned | 401; флаги не меняются |
| Valid PASSWORD_RESET | Новый BCrypt hash; used=true для reset-кодов за3days; JWT без изменений |
| Ban/unban/delete/profile edit | HTTP-команды перехода отсутствуют или STUB |

EmailRequest: type ACTIVATE/PASSWORD_RESET, used, createdAt. Expiry вычисляется как createdAt+3days, отдельного expiresAt нет. isActive() проверяет только время; сервис отдельно проверяет used и type. Used/expired code непригоден для повторного confirm и даёт400 BAD_REGISTRATION_REQUEST. Строки автоматически не удаляются. Проверка флага не обеспечивает exactly-once при конкуренции.

## Access и refresh

Access — подписанная строка до exp, без persisted revoke state. Logout и reset не переводят access в отдельное revoked-состояние. Удалённый user делает principal lookup ошибочным; стабильный auth response для этого случая не задан.

Refresh state = наличие DB row + revoked + подписанный exp. DB expires информационен для refresh-validation. Login создаёт новую row revoked=false; refresh ничего не меняет; single logout меняет revoked/revokedAt, bulk logout только revoked. Истечение времени не обновляет row. Revoked/expired rows остаются. Проверка revoked предшествует expiry после успешного user lookup. Состояний revive/rotation/last-used нет.

## FileItem, StoredObject, bytes

| DB / FS | Значение | Наблюдаемое API |
| --- | --- | --- |
| FileItem и StoredObject есть, bytes читаются | Обычный сохранённый файл | list/get/exists; download200 |
| Записи есть, bytes отсутствуют/нечитаемы | Логическое наличие без доступного файла | list/get/exists сохраняют наличие; download404 |
| Нет логической записи, bytes остались | Orphan physical | По прежнему ID недоступен |
| StoredObject без FileItem | Объект без ссылок, возможен вне обычного upload/copy flow | Отдельного object API нет; GC нет |
| Нет записей и bytes | Отсутствие / завершённое удаление | FileItem lookup404 |

Rename меняет originalName, move — folder. Copy создаёт новую identity, не версию. deletedAt UNUSED; API hard delete удаляет строки без tombstone. Optional FileMetadata отсутствует, если extractor не дал полей; это допустимое успешное состояние. Duration video не извлекается.

Upload использует только локальные tempFile/finalFile/movedToFinal; copy — copied. Эти переменные не переживают restart и не выдаются клиенту. Между final IO и DB commit может существовать объект без строки; после commit и сбоя cleanup — строка без bytes. Нет durable recovery state, прогресса по uploadId или запроса завершения.

## Категории папок и файлов

FolderType ROOT/CAMERA/FILES/USER задаётся при создании и не меняется API. ROOT ленивый; CAMERA/FILES появляются через default upload. Только USER можно rename/move/delete, причём delete требует пустоты. Длинные циклы предотвращает последовательная сервисная проверка, не формальная DB state machine.

FileType IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/OTHER — MIME-категория StoredObject. Она не меняется rename/move. Состояния локальной Android-очереди не принимаются сервером и не выводятся из этих enum.
