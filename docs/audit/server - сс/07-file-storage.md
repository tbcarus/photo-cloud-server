# 07. File Storage

---

## 1. Где физически находятся файлы

**[CONFIRMED]** Локальная файловая система процесса приложения. Внешних хранилищ (S3, MinIO, NFS-абстракция) нет.

| Параметр | Значение | Источник |
| --- | --- | --- |
| `storage.root` | `${STORAGE_ROOT:storage}` — по умолчанию относительный каталог `storage` рядом с рабочей директорией | `application.yml` |
| `storage.temp-dir` | `${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp}` — по умолчанию `<root>/tmp` | `application.yml` |
| Абсолютизация | `Paths.get(root).toAbsolutePath().normalize()` при каждом обращении | `StoragePathResolver.resolve()` |
| В тестах | временный каталог `Files.createTempDirectory("photo-cloud-server-it-")` | `AbstractIntegrationTest` |

**[CONFIRMED]** Каталог `storage/` добавлен в `.gitignore` (`/storage/`), но в рабочей копии присутствует с 12 реальными файлами — это локальные данные разработки.

---

## 2. Структура каталогов

**[CONFIRMED]** Схема из `StorageKeyGenerator`:

```
<storage.root>/
├── tmp/                                    ← storage.temp-dir (по умолчанию)
│   └── upload-<random>.tmp                 ← существует только во время upload
└── users/
    └── {userId}/
        └── objects/
            └── {checksum[0:2]}/            ← первый шард (2 hex-символа)
                └── {checksum[2:4]}/        ← второй шард (2 hex-символа)
                    └── {filename}
```

**Код:**
```java
public String generateFilePath(Long userId, String checksum) {
    String firstShard  = checksum.substring(0, 2);
    String secondShard = checksum.substring(2, 4);
    return "users/%d/objects/%s/%s".formatted(userId, firstShard, secondShard);
}
```

**Подтверждение на реальных данных** (каталог `storage/` в рабочей копии):

```
storage/users/3/objects/09/02/1. Черный кот с рыбкой.jpg_538a8a04-8f80-42b1-b1ad-5b496fd0ee68.jpg
storage/users/3/objects/38/88/Screenshot_20260317-112328 (1).png_5fb6115f-20db-413a-a747-3ca41c29ff84.png
storage/users/3/objects/f1/79/IMG_20260317_112411.jpg_7954a127-35bd-444f-b437-4ac5bb307ce6.jpg
```

**[CONFIRMED]** Шардинг по первым 4 hex-символам checksum даёт до 65 536 каталогов на пользователя — равномерное распределение, защита от переполнения одного каталога.

**[CONFIRMED]** Каталоги создаются лениво: `Files.createDirectories(finalFile.getParent())` перед move; `Files.createDirectories(tempDir)` перед созданием temp-файла.

**[CONFIRMED]** Дерево папок пользователя (`Folder`) в структуре хранилища **не отражается**. Хранилище — object storage; логическая иерархия существует только в БД.

---

## 3. Как строится имя файла

**[CONFIRMED]** `StorageKeyGenerator.generateFilename()` → `FilenameSanitizer.buildPhysicalFilename()`:

```
{limitedOriginalName}_{uuid}{.extension?}
```

Алгоритм (`FilenameSanitizer`):

1. `safeName(originalFilename)` — замена символов `< > : " / \ | ? *` и управляющих на `_`; строки `.`, `..` и пустая → `file`.
2. `extension(originalFilename, mimeType)` — расширение из имени после последней точки, приведённое к нижнему регистру, с удалением всего кроме `[a-z0-9]`, обрезкой до 20 символов. Если точки нет — расширение выводится из MIME по фиксированной таблице (`image/jpeg→jpg`, `image/png→png`, `image/gif→gif`, `image/webp→webp`, `video/mp4→mp4`, `audio/mpeg→mp3`, `application/pdf→pdf`, `application/zip→zip`; иначе — пустая строка).
3. `limitOriginalNameWithExtension(name, storage.physical-filename.original-name-max-length)` — по умолчанию **80** символов, с сохранением расширения.
4. Сборка: `limitedOriginalName + "_" + uuid + ("." + ext, если ext не пуст)`.
5. Финальная защита: если результат превышает 255 символов (`MAX_COMPONENT_LENGTH`), базовая часть дообрезается.

**Пример:** `IMG_20260609_202840.jpg` + MIME `image/jpeg` →
`IMG_20260609_202840.jpg_ecad797f-a9ee-44cd-8795-cbcde3ce8005.jpg`

**[CONFIRMED]** Заметьте: расширение исходного имени **остаётся внутри** физического имени (`...jpg_uuid.jpg`) — это видно и в реальных файлах хранилища.

### Ответы на подвопросы

| Вопрос | Ответ |
| --- | --- |
| Используется ли original filename? | **Да** — как префикс физического имени (после санитизации и обрезки до 80 символов) |
| Используется ли UUID? | **Да** — `UUID.randomUUID()` на каждый физический объект; именно он гарантирует уникальность |
| Используется ли checksum? | **В пути (каталогах)** — да, первые 4 символа. **В имени файла** — нет |
| Разделяются ли пользователи? | **Да** — `users/{userId}/` |
| Разделяются ли фото и видео? | **Нет** — все типы лежат в одном `objects/`. Разделение только логическое: папки `Camera` (IMAGE/VIDEO) и `Files` (остальное) |

**[CONFIRMED][RISK]** Оригинальные имена (включая кириллицу и пробелы) попадают на диск как есть, если прошли санитизацию. Это удобно при ручном разборе хранилища, но:
- раскрывает пользовательские имена файлов тому, у кого есть доступ к ФС;
- зависит от кодировки файловой системы (на разных ОС поведение с non-ASCII различается);
- `FilenameSanitizer` не обрабатывает зарезервированные имена Windows (`CON`, `PRN`, `NUL`, `COM1`…) и имена, начинающиеся с точки.

---

## 4. Поведение при конфликте имён

**[CONFIRMED]**

| Уровень | Поведение |
| --- | --- |
| Физическое имя | Коллизия практически невозможна благодаря UUID. Проверки на существование файла перед `move` **нет** — `Files.move()` без `REPLACE_EXISTING` бросит `FileAlreadyExistsException` → `500` |
| Логическое имя (`originalName`) | `FileItemService.ensureFileNameAvailable()`: если в папке (кроме `CAMERA`) уже есть файл с таким именем без учёта регистра → `FileConflictException` → `409` |
| Папка `CAMERA` | Проверка имени **пропускается** — одинаковые имена разрешены (типично для фото с телефона) |
| Auto-rename `file (1).jpg` | **Не реализовано** — TODO в коде: «Позже можно добавить overwrite/replace или auto-rename» |
| Overwrite/replace | **Не реализовано** |

---

## 5. Поведение при повторной загрузке

**[CONFIRMED]** Определяется в `FileItemService.uploadFile()`, строки 98–107:

```java
Optional<FileItem> existingInFolder = fileItemRepository
        .findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(user.getId(), folder.getId(), checksum);
if (existingInFolder.isPresent()) {
    deleteIfExists(tempFile);
    tempFile = null;
    return fileItemMapper.toDto(existingInFolder.get());
}
```

| Сценарий | Результат |
| --- | --- |
| Те же байты, **та же** папка | `200 OK` + **существующий** `FileItemDto`. Новых строк БД нет, новых файлов на диске нет. Идемпотентно |
| Те же байты, **другая** папка | Создаётся **новый** `StoredObject` + новый `FileItem` + **новая физическая копия** |
| Те же байты, та же папка, **другое имя файла** | Возвращается существующий `FileItem` со **старым** `originalName` — переданное имя игнорируется |
| Другие байты, то же имя, папка ≠ CAMERA | `409 CONFLICT` |
| Другие байты, то же имя, папка = CAMERA | Создаётся второй файл с тем же `originalName` |

**[CONFIRMED]** Дедупликации по содержимому между папками **нет** намеренно (комментарий в коде и `docs/application-overview.md` §10). Причина, заявленная в документации: это согласуется с логикой delete — удаление файла в одной папке не должно затрагивать другую.

**Следствие [CONFIRMED][RISK]:** пользователь, разложивший одну фотографию по 5 папкам, займёт на диске 5× места.

---

## 6. Временные файлы

**[CONFIRMED]**

| Аспект | Значение |
| --- | --- |
| Расположение | `storage.temp-dir` (по умолчанию `<root>/tmp`) |
| Создание | `Files.createTempFile(tempDir, "upload-", ".tmp")` |
| Назначение | принять поток, посчитать SHA-256 и размер, дать Tika и EXIF-экстрактору файл на диске вместо `byte[]` в памяти |
| Удаление при успехе | файл **перемещается** в final (`Files.move`) — исчезает из tmp |
| Удаление при дубле | `deleteIfExists(tempFile)` |
| Удаление при ошибке | общий `catch (RuntimeException | IOException)` в `uploadFile()` |
| Удаление при аварийном завершении JVM | **не происходит** — `deleteOnExit()` не используется, фоновой очистки нет |

**[CONFIRMED]** Дополнительно Spring сам создаёт временные файлы multipart: `spring.servlet.multipart.file-size-threshold: 0` означает, что **всё** содержимое пишется на диск (в стандартный temp-каталог Tomcat), а не буферизуется в памяти. Их жизненным циклом управляет контейнер.

**[CONFIRMED]** В коде оставлен TODO: «temp-dir должен оставаться на той же файловой системе, что и storage.root, чтобы move был дешёвым и по возможности atomic» — это конфигурационное требование не проверяется на старте.

**[CONFIRMED]** Тесты проверяют отсутствие «залипших» temp-файлов: `tempFileCount()` == 0 в `uploadOverMaxFileSizeReturnsErrorAndRemovesTempFile` и `tempFileIsRemovedWhenStreamingFails`.

---

## 7. Атомарность записи

**[CONFIRMED]** `FileItemService.moveTempToFinal()`:

```java
Files.createDirectories(finalFile.getParent());
try {
    Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
} catch (AtomicMoveNotSupportedException ex) {
    Files.move(tempFile, finalFile);   // fallback: не атомарно
}
```

| Условие | Атомарность |
| --- | --- |
| temp и final на одной ФС | **Да** — `ATOMIC_MOVE` (rename) |
| temp и final на разных ФС | **Нет** — fallback на copy+delete; при сбое возможен частично записанный final-файл |

**[CONFIRMED]** Комментарий в коде признаёт это: «Atomic move доступен не всегда; cleanup вокруг вызова всё равно удалит temp/final при ошибках».

**[CONFIRMED]** `fsync`/`FileChannel.force()` не вызывается — при отказе питания сразу после ответа данные могут не быть сброшены на носитель.

**[CONFIRMED]** `copy` (`FileItemService.copyFileForCurrentUser()`) использует `Files.copy(sourcePath, copiedPath)` — **не атомарен** и без `REPLACE_EXISTING`.

---

## 8. Порядок операций и сценарии сбоев

**[CONFIRMED]** Ключевое проектное решение — файловая операция выполняется **до** записи в БД:

```java
// Move делаем до БД: если файловая операция не удалась,
// в базе не появится ссылка на отсутствующий файл.
moveTempToFinal(tempFile, finalFile);
movedToFinal = true;
tempFile = null;
try {
    FileItem saved = createStoredObjectAndFileItem(...);   // транзакция БД
    return fileItemMapper.toDto(saved);
} catch (DataIntegrityViolationException ex) {
    deleteIfExists(finalFile);
    // гонка: вернуть уже существующий FileItem
} catch (RuntimeException ex) {
    deleteIfExists(finalFile);
    throw ex;
}
```

### Таблица сценариев сбоя

| # | Сбой | Состояние ФС после | Состояние БД после | Итог |
| --- | --- | --- | --- | --- |
| 1 | Разрыв соединения во время загрузки тела | temp удалён | пусто | **консистентно** |
| 2 | Превышен `max-file-size-bytes` | temp удалён | пусто | **консистентно**, `413` |
| 3 | Ошибка чтения входного потока | temp удалён | пусто | **консистентно** (тест `tempFileIsRemovedWhenStreamingFails`) |
| 4 | Ошибка `Files.move` | temp удалён (общий catch), final не создан | пусто | **консистентно** |
| 5 | **БД падает после успешного move** | final **удаляется** в catch | пусто | **консистентно** (тест `finalFileIsRemovedWhenDatabaseSaveFailsAfterMove`) |
| 6 | Гонка: параллельный upload того же checksum в ту же папку | свой final удалён, чужой остался | одна строка | **консистентно** (тест `raceConditionRemovesCurrentFinalFileAndReturnsExistingFileItem`) |
| 7 | **Процесс убит между `move` и commit БД** | final **остаётся** | пусто | **[RISK] orphan-файл** — cleanup невозможен |
| 8 | **Процесс убит во время записи temp** | temp **остаётся** | пусто | **[RISK] мусор в tmp** — очистки нет |
| 9 | Commit БД прошёл, ответ не доставлен клиенту | final есть | строка есть | консистентно; клиент повторит → идемпотентно вернётся тот же `FileItem` |
| 10 | `Files.deleteIfExists()` упал при delete | файл **остаётся** | строки удалены | **[RISK] orphan-файл**, только `log.error` |
| 11 | Ошибка `Files.copy` при copy | copied удаляется в catch | пусто | консистентно |
| 12 | БД падает после `Files.copy` | copied **удаляется** | пусто | консистентно |
| 13 | Удаление пользователя напрямую в БД | все файлы **остаются** | строки удалены | **[RISK] массовые orphan-файлы** |

**[CONFIRMED]** Что происходит **при ошибке БД после записи файла** — файл удаляется (сценарии 5, 12): обработано корректно.
**[CONFIRMED]** Что происходит **при ошибке записи файла после записи БД** — такой последовательности в коде нет: БД всегда пишется после файла. Единственное исключение — delete (сценарий 10).

---

## 9. Связь DB metadata ↔ physical file

**[CONFIRMED]** Единственный способ добраться до байтов:

```
FileItem.storedObject → StoredObject{filePath, filename}
       → StoragePathResolver.resolve(filePath, filename)
       → <storage.root>/<filePath>/<filename>
```

`StoragePathResolver`:

```java
Path rootPath   = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
Path targetPath = rootPath.resolve(filePath).resolve(filename).normalize();
if (!targetPath.startsWith(rootPath)) {
    throw new IllegalArgumentException("Invalid storage path");
}
```

**[CONFIRMED]** Полный путь **не хранится в БД** — соответствует пункту 18 в `TODO.txt`: «полный путь никогда не хранить в БД, а собирать только в сервисе».

Практические следствия:
- изменение `storage.root` (перенос хранилища) не требует миграции данных;
- защита от path traversal есть, хотя клиент никогда не передаёт компоненты пути напрямую;
- обратный поиск «физический файл → запись в БД» возможен только через `filename`, содержащий UUID (индекса под это нет).

**[CONFIRMED]** Naming нельзя восстановить в обратную сторону однозначно: `filePath` содержит первые 4 символа checksum, но полный checksum лежит только в БД.

---

## 10. Целевые проверки

### 10.1. Orphan files (файл есть на диске, записи в БД нет)

**[CONFIRMED] Возможны.** Источники:

| Источник | Механизм |
| --- | --- |
| Аварийное завершение между `move` и commit | сценарий 7 |
| Сбой `Files.deleteIfExists()` при delete | сценарий 10 (только лог) |
| Прямое удаление пользователя в БД | каскад БД не трогает диск |
| Ручное восстановление БД из более старого дампа | рассинхронизация |
| Брошенные temp-файлы | сценарий 8 |

**[CONFIRMED]** Обнаружения нет: ни сканера, ни отчёта, ни scheduled-задачи. Метрики «сколько байт занято на диске / сколько числится в БД» отсутствуют.

### 10.2. Orphan DB records (запись есть, файла нет)

**[CONFIRMED] Возможны** — если файл удалён вне приложения.

Поведение системы:
- `GET /files/{id}` — **вернёт `200`** с корректным DTO (метаданные читаются только из БД, диск не проверяется);
- `GET /files/{id}/download` — `404 FILE_ITEM_NOT_FOUND`, потому что `getDownloadResource()` проверяет `resource.exists() && resource.isReadable()`;
- `DELETE /files/{id}` — отработает нормально (`deleteIfExists` не бросает при отсутствии файла).

**[CONFIRMED]** Это подтверждено тестом `foreignMissingAndMissingPhysicalFilesReturnNotFound` (использует `deletePhysicalFile()` из `AbstractIntegrationTest`).

**[RISK]** Расхождение диагностируется только при попытке скачивания; список файлов останется «зелёным».

### 10.3. Cleanup

**[CONFIRMED] Полностью отсутствует:**

- нет `@Scheduled`-задач;
- нет очистки `tmp`;
- нет сверки БД ↔ ФС;
- нет удаления файлов при удалении пользователя;
- нет корзины/TTL для `deletedAt` (поле не используется);
- нет очистки протухших `refresh_token` и `email_requests`.

### 10.4. Duplicate files

**[CONFIRMED]** Физические дубликаты — **штатное состояние системы**, а не аномалия:

- один checksum × N папок = N физических копий;
- `copy` всегда создаёт новую копию, даже с тем же checksum;
- unique-ограничение на `(user_id, checksum)` для `stored_object` было и было **снято** в migration 13 — то есть дубликаты разрешены осознанно.

Дедупликация не реализована ни на уровне hardlink, ни на уровне reflink/CoW, ни на уровне переиспользования `StoredObject`.

**[CONFIRMED]** `StoredObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc()` существует и помечен TODO как заготовка для будущего server-side copy / link-existing, но **[UNUSED]**.

### 10.5. Missing physical files

См. 10.2. Дополнительно: **[CONFIRMED]** нет ни health-check, ни endpoint'а верификации целостности; checksum сохранённого файла после записи **не перепроверяется** (он вычисляется на лету при приёме потока и больше никогда не сверяется).

---

## 11. Ограничения размера

**[CONFIRMED]** Два независимых барьера:

| Барьер | Значение | Где проверяется | Ошибка |
| --- | --- | --- | --- |
| `spring.servlet.multipart.max-file-size` | `110MB` | Spring/Tomcat до входа в контроллер | `MaxUploadSizeExceededException` → `413 FILE_TOO_LARGE` «Multipart request exceeds configured limit» |
| `spring.servlet.multipart.max-request-size` | `110MB` | там же | то же |
| `storage.max-file-size-bytes` | `104857600` (100 MiB) | потоково в `FileUtils.writeAndCalculateSHA256()` | `FileSizeLimitExceededException` → `413 FILE_TOO_LARGE` «File size exceeds limit: N bytes» |

**[CONFIRMED]** Порядок: сначала срабатывает контейнерный лимит (110 MB), потом приложенческий (100 MiB). Зазор ~5 MB — файлы в этом диапазоне доходят до сервиса и отбрасываются при стриминге (temp-файл создаётся и удаляется).

**[CONFIRMED]** В тестах `storage.max-file-size-bytes` переопределяется в `1024` (`AbstractIntegrationTest.registerProperties()`), поэтому лимит покрыт тестом на малом объёме.

**[CONFIRMED]** Квот на пользователя (суммарный объём, количество файлов) нет.

---

## 12. Скачивание

**[CONFIRMED]** `FileController.downloadFile()`:

```java
FileItem file = fileItemService.getFileForCurrentUser(id, user);
Resource resource = fileItemService.getDownloadResource(file);   // UrlResource(path.toUri())
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.getStoredObject().getDetectedMimeType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalName() + "\"")
        .body(resource);
```

| Аспект | Поведение |
| --- | --- |
| Чтение | потоковое через `UrlResource` — файл целиком в память не загружается |
| Content-Type | `StoredObject.detectedMimeType` |
| Имя в заголовке | `FileItem.originalName` (логическое, не физическое) |
| HTTP Range (докачка) | **[CONFIRMED]** не поддерживается — `ResourceHttpRequestHandler` не задействован, заголовок `Accept-Ranges` не выставляется |
| ETag / Last-Modified / условные запросы | **не поддерживаются** |
| `Cache-Control` | не выставляется |
| Экранирование имени в `Content-Disposition` | **[RISK]** отсутствует: кавычка в имени сломает заголовок; non-ASCII без `filename*=UTF-8''` |
| Отсутствие файла | `404 FILE_ITEM_NOT_FOUND` |

**[RISK]** Отсутствие Range-запросов означает, что клиент не может докачать прерванный download большого видео — только начать заново.

---

## 13. Сводная таблица конфигурации хранилища

| Ключ | Default | Тип | Где используется |
| --- | --- | --- | --- |
| `storage.root` | `storage` (env `STORAGE_ROOT`) | `String` | `StoragePathResolver` |
| `storage.temp-dir` | `<root>/tmp` (env `STORAGE_TEMP_DIR`) | `String` | `FileItemService.createTempFile()` |
| `storage.max-file-size-bytes` | `104857600` | `long` | `FileItemService.writeToTempFile()` |
| `storage.physical-filename.original-name-max-length` | `80` | `int` | `StorageKeyGenerator.generateFilename()` |

**[CONFIRMED]** Класс `StorageProperties` дублирует значения по умолчанию в Java (`maxFileSizeBytes = 104857600L`, `originalNameMaxLength = 80`) — они совпадают с `application.yml`. Валидации (`@Validated`, `@NotNull`) на properties нет: при пустом `storage.root` приложение стартует и упадёт только при первой файловой операции.
