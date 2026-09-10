# 07. Физическое хранение файлов

## Где находятся байты

[CONFIRMED] (source: configuration) root = ${STORAGE_ROOT:storage}; temp-dir = ${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp}. Относительный root разрешается относительно working directory процесса, а не location jar. Для процесса из C:/projects/photo-cloud-server при отсутствии override это C:/projects/photo-cloud-server/storage. Реальный STORAGE_ROOT не считывался; содержимое существующей storage директории не исследовалось.

[CONFIRMED] Физическая схема:

~~~text
<absolute storage.root>/
  tmp/upload-<random>.tmp                 # если temp-dir по умолчанию
  users/<userId>/objects/<hash[0:2]>/<hash[2:4]>/
    <sanitized limited original name>_<UUID>[.<extension>]
~~~

Например, для исходного photo.jpg форма имени — photo.jpg_<UUID>.jpg: исходное расширение может входить в префикс и добавляется ещё раз в suffix. Это допустимый результат buildPhysicalFilename, а не опечатка схемы. Полный checksum в пути не используется: только4 первых hex symbols. UUID уменьшает вероятность коллизий физических имён. Папка user и sharding отделены от Folder tree.

[CONFIRMED] users/{id}/objects разделяет владельцев; IMAGE/VIDEO не разделены физически. Logical ROOT/Camera/Files/USER находятся только в PostgreSQL. Rename/move FileItem не меняют physical location. Copy создаёт другой UUID и независимый физический файл.

## Имя, расширение, тип

[CONFIRMED] FilenameSanitizer.safeName заменяет < > : " / \ | ? * и control chars на underscore, пустое/blank/./.. → file. limitOriginalNameWithExtension применяется с255 к logical name и с configurable80 к префиксу physical name. Rename/copy override дополнительно trim; upload имя не trim перед sanitize.

[CONFIRMED] Physical suffix = "_" + UUID + optional "." + extension. Итоговый physical component ограничивается255 Java characters. extension: последнее расширение исходного sanitized filename; lowercase, только a-z0-9, max20. Если расширения нет — MIME mapping JPEG/PNG/GIF/WebP/MP4/MP3/PDF/ZIP, иначе пусто. Клиентское расширение при наличии приоритетнее MIME, поэтому файл с image/jpeg содержимым и .bin именем может храниться с .bin.

[RISK] limitOriginalNameWithExtension сохраняет extensionWithDot целиком; если одно расширение само превышает лимит, логическое имя может остаться длиннее255. Физический component ограничивается позже, но SQL original_name может отклонить запись. Проверка255 symbols также не эквивалентна255 bytes на файловых системах с byte limit для UTF-8.

[CONFIRMED] MIME — Tika.detect(Path) над upload temp, multipart content-type не принимается как authoritative. FileType.fromMimeType классифицирует, но не запрещает OTHER/AUDIO/DOCUMENT/ARCHIVE. Расширение, MIME и категория могут отличаться от ожидания клиента.

## Upload: порядок записи

1. [CONFIRMED] file.isEmpty() → IllegalArgumentException400.
2. [CONFIRMED] createTempFile создаёт temp-dir и upload-*.tmp.
3. [CONFIRMED] FileUtils.writeAndCalculateSHA256 читает InputStream buffer8192; увеличивает size, проверяет size>limit, обновляет digest и пишет блок. Ровно limit допустимо.
4. [CONFIRMED] Tika определяет MIME; Drew extractor только image/*; capturedAt EXIF, иначе uploadedAt=now.
5. [CONFIRMED] folderId lookup либо getDefaultFolder; создание системных folders выполняется отдельно от file transaction.
6. [CONFIRMED] Find FileItem по user+folder+checksum. Найден — temp удалить, вернуть существующий DTO. Его physical file не проверяется и не восстанавливается.
7. [CONFIRMED] Для нового checksum name-check (кроме CAMERA). Конфликт409.
8. [CONFIRMED] Сгенерировать path/name; resolve normalized target within root.
9. [CONFIRMED] createDirectories(target.parent), Files.move(temp,final,ATOMIC_MOVE). При AtomicMoveNotSupportedException — fallback Files.move без ATOMIC_MOVE и без REPLACE_EXISTING.
10. [CONFIRMED] TransactionTemplate: INSERT StoredObject → FileItem (+Cascade metadata); commit → DTO →200.
11. [CONFIRMED] При DataIntegrityViolation после move: удалить собственный final, перечитать matching FileItem; если найден, вернуть его; иначе исходная ошибка. Иные RuntimeException после move → delete final, rethrow. Temp cleanup во внешнем catch.

[CONFIRMED] Spring multipart threshold0 отдельно задаёт staging multipart на диске контейнером; servlet multipart.location не указан. Сервис получает уже MultipartFile. Его temp-copy добавляется к контейнерному staging; сеть не стримится напрямую в final object.

## DB metadata ↔ bytes

| Слой | Ключ / что фиксирует |
| --- | --- |
| [CONFIRMED] FileItem | Клиентский id, owner, folder, logical name, dates, duplicated checksum |
| [CONFIRMED] StoredObject | owner, relative filePath, filename, size/checksum/MIME/type/extension |
| [CONFIRMED] Filesystem | bytes по root + filePath + filename |
| [CONFIRMED] FileMetadata | Извлечённые атрибуты конкретного FileItem |
| [CONFIRMED] Client DTO | StoredObject.size/checksum/MIME/type; path/name физического объекта не экспортируются |

Нет FK/constraint из БД к существованию physical path. Нет фонового пересчёта checksum, проверок size или восстановления object по temp. Path uniqueness не задана SQL. Путь root можно перенастроить независимо от БД — после этого старые metadata указывают в новый root.

## Матрица отказов

| Момент/отказ | Реализация и остаточное состояние |
| --- | --- |
| [CONFIRMED] Empty / oversize | Empty до temp; превышение service limit во время streaming → cleanup temp и413, file rows не создаются |
| [CONFIRMED] Ошибка InputStream/output/Tika | catch IO/runtime → попытка удаления temp, проброс; uniform storage-error DTO отсутствует |
| [CONFIRMED] Ошибка metadata | Extractor ловит Exception → WARN, пустые metadata, upload продолжается |
| [CONFIRMED] Чужая папка / name conflict | После streaming → temp cleanup,404/409, final/rows нет |
| [CONFIRMED] DB error после successful move | Попытка удаления final; DB transaction откатывается при ошибке внутри execute |
| [RISK] Ошибка mapper после commit | Mapper внутри try/catch upload/copy; runtime error вызывает удаление final при уже сохранённых rows |
| [RISK] Ошибка fallback move | movedToFinal ставится только после return; если filesystem оставила partial target при неуспешном non-atomic move, outer cleanup final не гарантирован |
| [RISK] Kill/power loss в streaming | finally/catch не выполняются; temp может остаться |
| [RISK] Kill между final move и DB commit | Orphan final, DB может не иметь ссылки |
| [RISK] Потеря ответа после commit | Клиент не знает результат; repeat тех же bytes/folder вернёт запись, пока она существует |
| [CONFIRMED] Delete DB failed | FS delete не запускается |
| [CONFIRMED] Delete FS failed после DB commit | Только ERROR log;204 остаётся; orphan больше не связан с client ID |
| [CONFIRMED] Copy DB failure после Files.copy | copied=true → best-effort cleanup copiedPath |
| [RISK] Copy IO прервался | copied=false до успешного возврата Files.copy; partial target при IOException может остаться |
| [CONFIRMED] Missing physical при download |404 FILE_ITEM_NOT_FOUND; row сохраняется |
| [CONFIRMED] Missing physical при copy | Прямой Files.copy может бросить IOException; специальной конвертации в404 нет |
| [RISK] Cleanup delete сам падает | IOException только логируется, файл может остаться |

[CONFIRMED] Atomic move — попытка атомарного перемещения пути, не атомарность операции DB+FS. fsync/force, durable journal, distributed transaction и reserve/commit protocol не реализованы. Работа temp и root на одной FS рекомендована только TODO, не проверяется.

## Delete и copy

[CONFIRMED] Owner определяется сравнением FileItem.user.id со StoredObject.user.id после lookup FileItem текущего пользователя. Owner delete сначала удаляет все FileItem с storedObject.id, flush, StoredObject в DB transaction; потом Files.deleteIfExists. Non-owner branch удаляет только свою logical entry. Удаление отсутствующих bytes не ошибка. Пустые sharding/directories не удаляются.

[CONFIRMED] Copy читает исходный StoredObject, проверяет target/name/checksum, копирует bytes, сохраняет новый объект и metadata. size/checksum/MIME/extension наследует из DB, физический файл повторно не анализируется. При новом имени с другим расширением filename генерируется по новому имени, но fileExtension копируется из source — возможное расхождение двух physical fields.

[CONFIRMED] Дедупликации физических объектов между папками или пользователями нет. В одной папке повтор upload возвращает old FileItem; copy того же checksum→409. Metadata.exists и checksum pre-check не являются доказательством физической сохранности.

## Уборка и защита

[CONFIRMED] Нет scheduled cleanup, reconciliation, quarantine, trash, lifecycle policy, retention, quota/space check, checksum scrub, thumbnail/preview/transcode jobs. Случайная коллизия final filename не имеет regenerate/retry loop. Поведение реальной FS при ATOMIC_MOVE на существующий target платформозависимо; полагаться на абсолютную защиту от overwrite только по UUID нельзя.

Источники: [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [storage components](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/storage), [FileUtils](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/util/FileUtils.java), [DrewFileMetadataExtractor](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/metadata/DrewFileMetadataExtractor.java).
