# Физические файлы

[CONFIRMED] Storage — java.nio.file.Files на файловой системе процесса. Нет S3/blob-provider SDK, сетевого upload gateway или remote object API. «Object storage» здесь описывает layout, а не внешний сервис. Источники: FileItemService.java, StorageProperties.java, StorageKeyGenerator.java, StoragePathResolver.java.

## Расположение и имя

~~~text
<storage.root>/
  tmp/upload-<random>.tmp                  # если temp-dir не переопределён
  users/<userId>/objects/<sha[0:2]>/<sha[2:4]>/
    <limited-safe-original-name>_<UUID>.<extension>
~~~

[CONFIRMED — config] storage.root = STORAGE_ROOT или относительное storage; temp-dir = STORAGE_TEMP_DIR или STORAGE_ROOT/storage fallback + /tmp. Root переводится в absolute normalized относительно process working directory. Для запуска из корня этого checkout default был бы C:\projects\photo-cloud-server\storage; это пример разрешения, не установленный production path. Реальные пользовательские файлы в аудите не открывались.

[CONFIRMED] Для photo.jpg физическое имя имеет форму photo.jpg_<UUID>.jpg: расширение остаётся в исходной части и повторяется в suffix. Original segment default 80 символов; физический component дополнительно ограничивается 255. checksum участвует в двух уровнях shard, не является полным physical filename. UUID генерируется заново upload/copy. Папки IMAGE/VIDEO на диске не разделяются; все категории одного пользователя идут в objects.

[CONFIRMED] FilenameSanitizer заменяет < > : " / \ | ? * и control chars на underscore. Пустота, '.' и '..' → file. Logical originalName ограничивается 255, rename/copy trim. Расширение берётся из original filename, sanitized alphanumeric/lowercase/max20; если расширения нет, используется небольшой MIME mapping jpg/png/gif/webp/mp4/mp3/pdf/zip, иначе empty. MIME определяет Tika по temp file, заголовок клиента не authoritative.

[RISK] limitOriginalNameWithExtension сохраняет suffix целиком: при расширении длиннее maxLength результат может превысить maxLength (base минимум 1 + весь suffix). Final filename снова ограничивается, но logical originalName может превысить SQL 255. OS byte-limit и Java UTF-16 character limit также не одно и то же. RESERVED Windows device names/trailing dot/space отдельно не нормализуются.

[CONFIRMED] Path resolver root.resolve(filePath).resolve(filename).normalize() проверяет startsWith(root). [RISK] Это lexical containment, без toRealPath и разрешения symlinks/junctions. Клиентские / и \ удаляются sanitizer, но доступ администратора к storage и его symlinks лежит вне HTTP boundary.

## DB metadata ↔ bytes

| DB поле | Роль |
| --- | --- |
| file_item.id | API logical file ID |
| file_item.folder_id/original_name | UI размещение/имя; не путь на диске |
| file_item.stored_object_id | Ссылка на physical descriptor |
| stored_object.user_id | Physical owner |
| stored_object.file_path/filename | Relative directory и component для чтения/удаления |
| stored_object.checksum/size/MIME/type | Вычислены при upload, на download не валидируются |

[CONFIRMED] SQL 13 разрешает SO с одинаковым checksum; SQL 14 unique только FileItem user+folder+checksum. Между разными папками byte dedup не выполняется. Имя вне CAMERA проверяется case-insensitive сервисом; DB unique имени нет. Physical path/filename unique constraint удалён вместе со storage_key в SQL 11.

## Upload и durability

1. [CONFIRMED] MultipartFile.isEmpty → ошибка для empty.
2. [CONFIRMED] Files.createTempFile в temp-dir; input → output, буфер 8192; SHA-256 и size. max 100 MiB, превышение до записи следующего chunk → FileSizeLimitExceededException.
3. [CONFIRMED] Tika MIME → FileType; Drew image metadata; capturedAt fallback uploadedAt.
4. [CONFIRMED] Target folder lookup/default lazy creation, logical filename sanitation.
5. [CONFIRMED] Existing FileItem по user/folder/checksum → delete temp и вернуть прежний DTO без проверки final file.
6. [CONFIRMED] Имя free? → generate shard/UUID → create final parents → Files.move(ATOMIC_MOVE), fallback Files.move при AtomicMoveNotSupportedException.
7. [CONFIRMED] TransactionTemplate создаёт SO + FileItem + optional cascade metadata; commit завершается до возврата execute.
8. [CONFIRMED] DataIntegrityViolationException: удалить свой final, перечитать tuple и вернуть raced winner, если найден. Остальные runtime DB ошибки: удалить final и бросить дальше.
9. [CONFIRMED] Внешний catch RuntimeException|IOException удаляет temp и final, если movedToFinal; cleanup IOException лишь логируется.

[CONFIRMED] Собственный этап записи не вызывает MultipartFile.getBytes(). Но это не доказательство ограничения памяти всего HTTP pipeline: servlet multipart preprocessing и response logging отдельны. Лимит servlet file/request 110MB, threshold=0. Temp и final желательно на одной filesystem — это комментарий TODO, конфигурация допускает другое.

[RISK] Atomic move условный: fallback не атомарен, fsync/force нет, общего durable commit disk+DB нет. Ошибка/авария между final move и DB commit оставляет orphan при падении процесса; ошибки после commit при mapping DTO попадают в catch, который может удалить байты уже сохранённого descriptor. Прямое отображение DTO в try охватывает больше, чем только DB operation.

## Copy, rename/move и delete

[CONFIRMED] Copy проверяет name/checksum в target, Files.copy без temp и без ATOMIC_MOVE, после успеха флаг copied=true, затем DB transaction с новым SO/FileItem и metadata clone. Не пересчитывает содержимое/checksum/MIME/size: берёт поля source SO. fileExtension копирует из source, хотя generated filename extension может опираться на новое имя. Rename/move не трогают disk.

[RISK] Если Files.copy создал partial target и затем выбросил IOException до copied=true, catch target не удаляет. Аналогично fallback move не гарантирует cleanup частично созданного final: movedToFinal выставляется только после успешного return. Такие failure windows не покрыты найденными тестами.

[CONFIRMED] Owner delete сначала коммитит удаление DB ссылок и SO, затем Files.deleteIfExists. Ошибка удаления → ERROR log и всё равно 204. Nonowner SO удаляет только logical FileItem. Empty directory cleanup нет. User delete через API не реализован; SQL cascade сам не удаляет bytes.

## Таблица сбоев и восстановление

| Ситуация | Реакция кода | Остаток / восстановление |
| --- | --- | --- |
| Streaming error / >100 MiB | catch, cleanup temp, exception | Обычно без file rows; cleanup failure лишь log |
| MIME IOException | cleanup temp | Unified HTTP error не задан |
| Metadata exception | WARN, empty metadata | Upload продолжается, capturedAt fallback |
| Folder owner/name conflict | cleanup temp | Lazy system folders могли уже сохраниться |
| DB failure после final move | rollback transaction + delete final | Cleanup best effort, процесса crash не покрывает |
| Параллельный upload same tuple | constraint, cleanup loser, lookup winner | Возврат winner при корректном race recovery |
| Crash до/после move | Java catches не выполняются | Temp/final orphan; scanner отсутствует |
| Crash после DB commit | Row+file обычно уже есть | Клиент мог не получить ID/200 |
| Отсутствующие bytes, row существует | Card/list/exists продолжают видеть row; download 404 | Upload duplicate возвращает row, repair отсутствует |
| Delete IOException после commit | ERROR, HTTP 204 | Orphan bytes, retry DELETE даст 404 |
| Повреждённые bytes | Download не сверяет checksum | Можно получить иное содержимое с прежним metadata |

[CONFIRMED] Reconciliation jobs, garbage collector, quota accounting, checksum verify на download, orphan-report endpoint, delayed deletion queue не найдены. Никакой фактический orphan не объявляется найденным: содержимое storage не обследовалось.

