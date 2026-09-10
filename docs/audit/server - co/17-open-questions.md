# 17. Открытые вопросы

Здесь нет предложенных ответов. [INFERRED] означает, что необходимость уточнения следует из кода, а требование по нему однозначно восстановить нельзя. Фактические решения (например user-folder-checksum uniqueness) не объявляются неизвестными: вопрос задаётся о желаемой семантике, не о текущей реализации.

## Развёртывание и данные

| ID | Вопрос | Почему код не отвечает / evidence |
| --- | --- | --- |
| Q1 [INFERRED] | Какой revision и какие changesets реально развёрнуты? | Audit только HEAD и SQL; DATABASECHANGELOG не читался |
| Q2 [INFERRED] | Есть ли сохранённые legacy media_file данные и проходила ли migration10 на непустой БД? |10 DROP без data migration |
| Q3 [INFERRED] | Проверены ли существующие duplicates перед14? Как сохранять их смысл при upgrade? | read-only db/audit script без политики разрешения |
| Q4 [INFERRED] | Где реально находятся root/temp, как они монтируются, есть ли shared volume? | Environment/FS deployment неизвестны |
| Q5 [INFERRED] | Каковы backup/restore/retention/размеры пользовательского архива и допустимое окно потери? | Отсутствуют repo operational policies |
| Q6 [INFERRED] | Какие reverse proxy/TLS/forwarded-host правила действуют? | Email link зависит от request scheme/host; config не описывает proxy |
| Q7 [INFERRED] | Как .env.local/.env.docker передаются процессу? | Файлы существуют, чтение запрещено; loader не найден |
| Q8 [INFERRED] | Какие SLAs, лимиты пользователей/диска/параллельных transfers предполагаются? | Только per-file limit и batch500 |

## Клиент и синхронизация

| ID | Вопрос | Подтверждённая граница |
| --- | --- | --- |
| Q9 [INFERRED] | Какая версия Android использует какие aliases/routes? | Server tests не подтверждают actual Android usage |
| Q10 [INFERRED] | Как клиент должен получить cameraId до первого checksum pre-check? | ROOT GET не создаёт CAMERA; exists requires folderId |
| Q11 [INFERRED] | Что делать с локальным файлом после server delete или move из CAMERA? | Нет delta feed/tombstones, folder checksum может стать missing |
| Q12 [INFERRED] | Должен ли local deletion приводить к remote delete? | Сервер не знает local state |
| Q13 [INFERRED] | Как хранить связь local asset ↔ FileItem.id/folder/checksum? | Same checksum может иметь несколько IDs в разных папках |
| Q14 [INFERRED] | Как интерпретировать existing при отсутствии physical bytes? | API проверяет logical row; repair protocol нет |
| Q15 [INFERRED] | Как согласовать local rename и server originalFilename при duplicate? | Upload возвращает старое имя |
| Q16 [INFERRED] | Как клиенту отображать move checksum conflict500? Должен ли продукт отличать его от временной ошибки? | Current handler generic DB constraint code |
| Q17 [INFERRED] | Как должно трактоваться время съёмки без timezone, особенно EXIF и видео? | capturedAt local time; video fallback upload time |
| Q18 [INFERRED] | Какие действия/состояния нужны при потере response после commit? | Нет upload session/status lookup по request ID |

## Аутентификация и почта

| ID | Вопрос | Подтверждённая граница |
| --- | --- | --- |
| Q19 [INFERRED] | Ожидается ли немедленная блокировка access после ban/logout/reset? | Сейчас нет, refresh state отдельно |
| Q20 [INFERRED] | Нужна ли связь refresh с устройством и список «сессий» пользователю? | Только userName/token, несколько login rows |
| Q21 [INFERRED] | Как пользователь должен завершать reset из письма при page501? | JSON confirm реализован, browser page нет |
| Q22 [INFERRED] | Что считать успехом register/reset-request при недоставленном письме? | Message заявляет sent; delivery tracking нет |
| Q23 [INFERRED] | Какой срок/лимит email codes является актуальным требованием? | Код3days, README-description упоминает1day reset и TODO limits |
| Q24 [INFERRED] | Каковы правила редактирования displayName/email/settings? | Endpoints501, request models не определены |
| Q25 [INFERRED] | Как должны удаляться учётная запись, refresh rows и bytes? | User deletion API нет; SQL cascades не удаляют files |

## Файлы и организация

| ID | Вопрос | Подтверждённая граница |
| --- | --- | --- |
| Q26 [INFERRED] | Планируется ли использовать cross-owner StoredObject references в данных? | Model/test допускают, публичный share/link API отсутствует |
| Q27 [INFERRED] | Каковы будущие правила удаления owner object с чужими ссылками? | Current owner delete удаляет все references |
| Q28 [INFERRED] | Что означает зарезервированный deletedAt и потребуется ли trash/restore? | Current hard delete; state не реализован |
| Q29 [INFERRED] | Должны ли explicit upload/move разрешать документы в CAMERA и images в FILES? | Сейчас ограничивается только ownership, тип влияет на default |
| Q30 [INFERRED] | Как продукт трактует copy без targetFolderId, если он всегда конфликтует в source folder? | DTO optional, uniqueness отвергает same checksum |
| Q31 [INFERRED] | Требуются ли client-supplied capturedAt/metadata для видео и файлов без EXIF? | Текущий upload таких полей не принимает |
| Q32 [INFERRED] | Должна ли серверная copy проверять фактическую checksum/size source bytes? | Сейчас доверяет DB physical metadata |
| Q33 [INFERRED] | Какая политика для conflicting names и case/unicode normalization? | Сейчас sanitize+case-insensitive precheck кроме CAMERA, без Unicode normalization/DB uniqueness |

Источники вопросов: [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [FolderService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java), [auth services](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [README-description](C:/projects/photo-cloud-server/README-description.md), [migrations](C:/projects/photo-cloud-server/src/main/resources/db/changelog/table). До отдельных решений эти вопросы не дополняют As-Is новыми требованиями.
