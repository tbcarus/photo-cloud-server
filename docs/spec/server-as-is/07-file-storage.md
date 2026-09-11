# File storage

## Физическая модель и имена

Байты хранятся локально. `storage.root` по умолчанию `storage`, относительно working directory процесса. `storage.temp-dir` по умолчанию `${STORAGE_ROOT:storage}/tmp`. Итоговый путь: root + StoredObject.filePath + StoredObject.filename.

```text
<storage.root>/
  tmp/upload-<random>.tmp   # только при default-связке root/temp; иначе — отдельный storage.temp-dir
  users/<userId>/objects/<checksum[0:2]>/<checksum[2:4]>/
    <sanitized original name>_<UUID>[.<extension>]
```

`storage.root` и `storage.temp-dir` читаются отдельно; temp может находиться вне root. Схема `root/tmp` верна только при default storage.temp-dir и root из STORAGE_ROOT/default: override только `storage.root` не обязан изменять temp-dir. Логические root/Camera/Files/USER-папки не представлены physical directories. Переименование и перемещение логического файла не меняют путь объекта.

Sanitizer заменяет `< > : " / \ | ? *` и управляющие символы на underscore; blank, `.` и `..` превращаются в `file`. Upload не делает предварительный trim имени; rename/copy override делают. Logical name ограничивается функцией сохранения расширения до 255 Java characters, но расширение длиннее лимита способно нарушить этот предел. Physical prefix по умолчанию 80; итоговый physical component обрезается до 255 characters. Это не проверка байтового лимита конкретной FS.

Расширение берётся после последней точки, lowercased, очищается до a-z0-9 и обрезается до20. Если точки с расширением нет, MIME fallback: JPEG→jpg, PNG→png, GIF→gif, WebP→webp, MP4→mp4, MPEG audio→mp3, PDF→pdf, ZIP→zip; иначе пусто. Имя `photo.jpg` может дать `photo.jpg_<UUID>.jpg`. При существующем расширении MIME его не переопределяет. UUID уменьшает вероятность коллизии, но не является SQL constraint или строгой гарантией невозможности совпадения.

Resolver использует absolute normalized root и lexical startsWith(root). Проверки realpath/symlink/reparse points нет. Каталоги не очищаются после удаления последнего объекта.

## Upload и повторная загрузка

Сервис отклоняет empty file, создаёт temp и читает InputStream блоками 8192 bytes, считая SHA-256 и фактически прочитанный размер. Превышение 104857600 bytes даёт 413; ровно лимит допустим. Tika определяет MIME из temp, FileType — из MIME, extractor получает metadata только image/*. capturedAt = EXIF Original/Digitized в system timezone либо uploadedAt=now.

После чтения выполняется explicit folder ownership lookup или выбор default: IMAGE/VIDEO→CAMERA, остальное→FILES. Создание default folders завершается отдельно от file transaction. Любой свой тип папки допустим при explicit upload/move; CAMERA не является ограничением допустимого MIME.

MIME allowlist/blacklist для ограничения допустимых upload-типов отсутствует. Detected MIME/FileType используется для классификации и выбора default folder; upload произвольного типа этим механизмом не запрещается. Антивирусной проверки в приложении нет.

Затем проверяется FileItem по `(user, folder, checksum)`. При совпадении temp удаляется best effort и возвращается старый DTO с 200. Новое имя, времена и metadata не применяются; физические bytes существующего объекта не проверяются и не восстанавливаются. При иной папке создаётся новая пара и независимые bytes. Побайтовое сравнение при одинаковом SHA-256 отсутствует: гипотетическая hash collision трактуется как duplicate.

Для нового checksum logical filename conflict вне CAMERA проверяется без учёта регистра и даёт409: `IMG.JPG` и `img.jpg` конфликтуют в соответствующей папке. Внутри CAMERA совпадение имени разрешено. Новый final path получает UUID; temp перемещается с ATOMIC_MOVE. При AtomicMoveNotSupportedException повторяется обычный Files.move. DB transaction затем сохраняет StoredObject, FileItem и optional metadata. После commit строится DTO.

## Move, copy, delete

| Операция | Логические записи | Физический объект |
| --- | --- | --- |
| Rename | Изменяется originalName после name check | Прежний filename/path/metadata |
| Move | Изменяется folder; name check, без checksum pre-check | Прежний объект и bytes |
| Copy | Новый FileItem, uploadedAt=now; capturedAt и metadata от source | Files.copy, новый StoredObject/UUID; size/checksum/MIME/type/extension из source DB |
| Owner delete | В transaction все references + metadata, затем StoredObject | deleteIfExists после commit |
| Non-owner delete | Только текущий FileItem и metadata | StoredObject и bytes сохраняются |

Copy не использует physical dedup и не пересчитывает source checksum/size. При изменении расширения имени копии filename строится по новому имени, а StoredObject.fileExtension наследуется от source. Copy в source folder всегда конфликтует; в target с тем же checksum →409. Move может упасть на DB checksum unique с500, если name check не остановил операцию раньше.

Удаление объекта не ждёт последней ссылки. Owner-delete удаляет их все, включая потенциальные cross-owner references; API создания таких references отсутствует. Non-owner-delete не удаляет StoredObject даже после исчезновения последней ссылки. Отсутствующие bytes не препятствуют успешному delete; IOException удаления только логируется, ответ остаётся204.

## Согласованность и компенсация

| Событие | Действие сервера | Возможное остаточное состояние |
| --- | --- | --- |
| Stream/MIME/folder/name error | Попытка удалить temp, проброс ошибки | Temp возможен при cleanup failure |
| Ошибка metadata extractor | WARN и пустой результат, upload продолжается | Metadata может отсутствовать при успешном upload |
| Ошибка DB transaction после final move/copy | Rollback transaction, попытка удалить собственный final | Orphan bytes при cleanup failure |
| Upload DataIntegrityViolation | Удалить свой final; reread user/folder/checksum; вернуть найденный DTO или пробросить ошибку | Это ограниченная компенсация, без повторного INSERT |
| Runtime mapping error после commit | Cleanup catch пытается удалить final | Строки БД могут остаться без bytes |
| IO error внутри move/copy до успешного return | Flags movedToFinal/copied ещё false | Частичный target не гарантированно очищается |
| Kill/power failure | Catch не выполняется | Temp, orphan final либо недоступные bytes |
| DB delete failure | Physical delete ещё не начат | Файловые bytes сохраняются |
| FS delete failure после commit | ERROR log, без retry | Orphan с ответом204 |
| Missing/unreadable physical download | 404 FILE_ITEM_NOT_FOUND | FileItem остаётся и виден в list/exists |

DB и FS не имеют общей атомарной транзакции. ATOMIC_MOVE — попытка атомарного перемещения пути, с fallback; durable journal/fsync/repair jobs отсутствуют. Код не задаёт переносимую гарантию collision/overwrite для ATOMIC_MOVE на уже существующий target. Изменение storage.root меняет разрешение прежних DB paths без миграции байтов.

Download отдаёт UrlResource после exists/isReadable, без повторного checksum. HttpLoggingFilter кэширует полный response, поэтому end-to-end download не имеет гарантии постоянной памяти. Framework Range/conditional headers остаются OPEN в [05](05-api.md). Orphan reconciliation, quota, retention, trash и автоматическое восстановление отсутствуют в приложении.
