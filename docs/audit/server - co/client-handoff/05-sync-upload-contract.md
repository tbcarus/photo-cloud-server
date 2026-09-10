# 05. Серверная сторона sync/upload

[CONFIRMED] Это pre-check + single file upload, а не полная синхронизация. Нет device ID, sync sessions, tombstones, change cursor, upload reservation/chunks/progress или explicit complete ACK.

## 1. Область сравнения

Ключ дубликата — **текущий пользователь + конкретная folderId + SHA-256 bytes**. Ни исходное имя, ни дата, ни размер сами по себе не определяют duplicate. Те же bytes в другой folder того же user или у другого user — новая независимая загрузка.

[CONFIRMED] Все операции protected access. Для pre-check обязательно существование своей folder. GET /api/v1/folders/root лениво создаёт только ROOT. GET /folders/{id}/children читает только direct children и не создаёт Camera/Files. Эти системные папки появляются при первом default upload соответствующего типа. Поэтому server не предоставляет отдельного способа «ensure Camera перед первым exists». Создать USER с именем Camera в ROOT тоже нельзя409.

## 2. Проверка checksum

POST /api/v1/files/checksums/exists:
~~~json
{"folderId":123,"checksums":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}
~~~
[CONFIRMED] folderId Long not-null; checksums nonempty; каждый string nonblank и exactly64 hex, регистр любой. Spaces не trim. Максимум500 **исходных элементов**, включая duplicates. После проверки count сервер делает lowercase и dedup с сохранением first-seen order.

Response200:
~~~json
{"existing":[],"missing":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]}
~~~

[CONFIRMED] Ответ partition: каждый уникальный normalized input попадает один раз в existing либо missing; порядок внутри каждой соответствует исходному первому появлению. Existing не содержит fileId/name/size/folderId: только hashes. Проверяется logical FileItem, не доступность physical bytes. Missing в target не исключает совпадение в другом folder.

[CONFIRMED] Ошибки:400 validation/body/batch;404 folder missing/foreign;401 access. Проверка read-only, не резервирует checksum и не требует дальнейшего upload. Между pre-check и upload другой запрос может создать/удалить/move объект.

## 3. Upload

POST /api/v1/files либо POST /api/v1/files/upload:
~~~text
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data; boundary=<boundary>

part file: один файл, filename=<original name>, raw bytes
param folderId: optional int64
~~~

[CONFIRMED] Нет body checksum, mediaId, localUri, status, client timestamps или declared MIME contract. Multipart MIME server не считает authoritative. Сервер сам вычисляет SHA-256 lowercase и фактический size, Tika MIME и FileType. Metadata best-effort только image; capturedAt=EXIF date либо uploadedAt=server now.

[CONFIRMED] Explicit folderId допускает любую свою папку; автоматически тип не ограничивается. Без folderId IMAGE/VIDEO→CAMERA, остальные→FILES. Чтобы pre-check и upload имели одинаковый scope, target должен совпадать; иначе результат pre-check не описывает upload target.

[CONFIRMED] Не пустой file; service limit104857600 bytes (100MiB), container max-file/request110MB, multipart overhead относится к request limit. Service checks после принятия MultipartFile; повтор duplicate тоже требует streaming всего файла для вычисления hash.

## 4. Подтверждение результата

[CONFIRMED]200 FileItemDto означает завершение server method с новым либо существующим logical file. Два случая имеют одинаковые status и DTO shape; флага created/alreadyUploaded нет. Новый объект получает id/folderId, normalized originalFilename, server size/hash/type/MIME/dates. Physical paths/StoredObject ID не выдаются.

[CONFIRMED] Если checksum уже есть в target: возвращается старый FileItemDto, в том числе **старые originalFilename, capturedAt, uploadedAt**. Новое имя в multipart не применяется; metadata не переизвлекаются в existing record. Same-folder duplicates не создают bytes/DB rows. Повторный upload не лечит отсутствие старых bytes на диске.

[CONFIRMED] Если checksum отсутствует: вне CAMERA проверяется имя case-insensitive. То же имя с другими bytes→409; CAMERA допускает разные bytes с одинаковым logical name. Bytes в иной папке→новый StoredObject и FileItem. Нет overwrite/version/auto-rename.

## 5. Частичный upload / interruption / retry

| Ситуация | Серверное поведение | Вывод для анализа клиента |
| --- | --- | --- |
| [CONFIRMED] Upload rejected400/404/409/413 | Temp best-effort cleanup; файл не создаётся этим успешным pipeline | Исправить вход/target; retry тех же oversized bytes бесполезен |
| [CONFIRMED] Поток чтения падает | Cleanup temp, IO propagates; unified JSON500 нет | Возможен отсутствующий/нестандартный response |
| [INFERRED] Сеть потеряна до получения200 | Client не знает, был ли commit | Сверить ту же folder/hash либо retransmit same bytes |
| [CONFIRMED] Same bytes/folder после commit | Existing DTO200 | Идемпотентность ограничена неизменным target/content/state |
| [INFERRED] Local file изменился между hash и upload | Сервер вычислит другой hash по реально переданным bytes | Нельзя считать старый pre-check подтверждением новых bytes |
| [CONFIRMED] Есть row, нет bytes | exists=existing, metadata200, download404, повтор upload старый DTO200 | Нет server repair протокола через upload |
| [CONFIRMED] DB error после final move | Best-effort own final cleanup, checksum race может стать200 | Error status не всегда значит «никто не загрузил» |
| [INFERRED] Процесс убит | Catch cleanup не исполнится | Client-visible resume/state отсутствует |
| [CONFIRMED] Copy success, response потерян | Повтор copy в target→409 при checksum |409 не возвращает copied ID; можно list target |
| [CONFIRMED] Delete success, response потерян | Повтор404 | Состояние «нет ID» достигнуто; FS cleanup статус отдельно неизвестен |

[CONFIRMED] GET /files/checksums — весь user list {id,originalFilename,checksum}, без folderId, pagination и ordering. Не подмена folder-scoped exists: одинаковый checksum может встречаться несколько раз.

[CONFIRMED] Для получения ID после exists сервер не имеет endpoint lookup-by-checksum→DTO. Можно получить страницы GET /files?folderId=... или same-bytes upload response; конкретный выбор алгоритма клиента этим аудитом не устанавливается.

## 6. Локальные состояния и несколько устройств

[CONFIRMED] Сервер не принимает NEW/UPLOADING/UPLOADED/FAILED состояния клиента, не хранит localUri/MediaStore id и не знает удаления на телефоне. Несколько login/refresh account допустимы, но устройства не различаются.

[INFERRED] Move/delete на сервере может сделать ранее загруженный hash missing в CAMERA; без отдельной клиентской политики следующий scan способен загрузить его снова. Текущий сервер не сигнализирует, было ли отсутствие результатом пользовательского delete, move или никогда не загруженного файла.

Источник: [FileController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [ChecksumSyncService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncService.java), [FolderService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java). Все client-relevant правила изложены здесь без необходимости читать эти файлы.
