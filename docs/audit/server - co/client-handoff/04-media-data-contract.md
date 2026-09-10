# 04. Поля данных на границе server/client

[CONFIRMED] Здесь все21 DTO из model/dto, плюс ErrorResponse и простые controller response maps. Типы — JSON/wire; int64 означает Java Long/long, int32 Integer/int. Nullable у responses описывает нормальную сохранённую модель и SQL constraints; сами Lombok DTO не имеют @NotNull, поэтому это не schema-level запрет JSON null во всех возможных повреждённых данных.

Ownership: CLIENT_SUPPLIED — поступает в запросе; SERVER_GENERATED — формируется сервером; SERVER_AUTHORITATIVE — сервер возвращает принятое/вычисленное значение, которое клиент не может произвольно записать данным API; CLIENT_LOCAL_ONLY — не передаётся в server API. Для client-origin bytes-derived fields authoritative не означает независимость от содержимого, предоставленного клиентом.

## FileItemDto

| Field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| id | number int64 | Нет у persisted item | Логический FileItem ID; параметр /files/{id}, не StoredObject ID | SERVER_GENERATED |
| folderId | number int64 | Нет | ID текущей логической папки | SERVER_AUTHORITATIVE |
| originalFilename | string | Нет | Нормализованное логическое имя; duplicate возвращает прежнее | SERVER_AUTHORITATIVE |
| mimeType | string | Нет | Tika detected MIME из physical object; не multipart header | SERVER_AUTHORITATIVE |
| size | number int64 | Нет | Реально прочитанный размер в bytes | SERVER_AUTHORITATIVE |
| checksum | string | Нет | Lowercase SHA-256 hex64; response берётся из StoredObject | SERVER_AUTHORITATIVE |
| fileType | string enum | Нет | IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER | SERVER_AUTHORITATIVE |
| capturedAt | string local datetime | Нет | EXIF Original/Digitized либо uploadedAt; copy наследует source | SERVER_AUTHORITATIVE |
| uploadedAt | string local datetime | Нет | Server now при new upload/copy; duplicate сохраняет старый | SERVER_GENERATED |
| deletedAt | string local datetime | Да, обычно null | Зарезервирован; current delete hard, nonnull не создаётся API | SERVER_AUTHORITATIVE |
| metadata | FileMetadataDto object | Да | Отсутствует при no extracted metadata; не error state | SERVER_AUTHORITATIVE |

[CONFIRMED] Тело не содержит userId, physical filePath/filename/fileExtension, storageKey, StoredObject ID, localUri, status, uploadId, thumbnailUrl. Имя response originalFilename отличается от request originalName rename/copy.

## FileMetadataDto

Все значения optional; dimensions только для JPEG/PNG ветвей extractor. Duration API field есть, но текущая extraction не заполняет его для video/audio.

| Field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| width | number int32 | Да | Ширина изображения в pixels | SERVER_AUTHORITATIVE |
| height | number int32 | Да | Высота изображения в pixels | SERVER_AUTHORITATIVE |
| durationSec | number int32 | Да | Поле длительности seconds; текущий extractor не задаёт | SERVER_AUTHORITATIVE |
| cameraMake | string | Да | EXIF manufacturer | SERVER_AUTHORITATIVE |
| cameraModel | string | Да | EXIF model | SERVER_AUTHORITATIVE |
| lensModel | string | Да | EXIF lens model | SERVER_AUTHORITATIVE |
| exposureTime | string | Да | EXIF textual value, не guaranteed numeric seconds | SERVER_AUTHORITATIVE |
| fNumber | JSON decimal number | Да | EXIF rational; точное wire имя fNumber закреплено @JsonProperty | SERVER_AUTHORITATIVE |
| iso | number int32 | Да | EXIF ISO | SERVER_AUTHORITATIVE |
| focalLength | JSON decimal number | Да | EXIF focal length; отдельного units поля нет | SERVER_AUTHORITATIVE |
| latitude | JSON decimal number | Да | GPS degrees | SERVER_AUTHORITATIVE |
| longitude | JSON decimal number | Да | GPS degrees | SERVER_AUTHORITATIVE |

[CONFIRMED] В БД fNumber/focalLength NUMERIC(10,4), GPS NUMERIC(10,7); response upload маппится после save из entity, а subsequent read из БД может отразить её scale/rounding. Контракт точной текстовой формы десятичных чисел не задан. Нет orientation/timezone/altitude/codec/frameRate/client-metadata-update DTO.

## FolderDto

| Field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| id | int64 | Нет | ID folder | SERVER_GENERATED |
| parentId | int64 | Да у ROOT | ID непосредственного parent | SERVER_AUTHORITATIVE |
| name | string | Нет | root/Camera/Files либо нормализованное user name | SERVER_AUTHORITATIVE |
| folderType | enum string | Нет | ROOT/CAMERA/FILES/USER | SERVER_AUTHORITATIVE |
| createdAt | local datetime string | Нет | Создание folder | SERVER_GENERATED |
| updatedAt | local datetime string | Нет | Hibernate update timestamp | SERVER_GENERATED |

[CONFIRMED] Children отдельно, не поле FolderDto. FileCount/path/deviceId нет. Тип системных folders через API не изменяется. Case-insensitive siblings names; root Camera/Files reserved.

## PageResponse<FileItemDto>

| Field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| items | array FileItemDto | Нет, может быть [] | Текущая page | SERVER_GENERATED |
| page | int32 | Нет | Номер с0 | SERVER_GENERATED |
| size | int32 | Нет | Запрошенный page size, не items.length | SERVER_GENERATED |
| totalElements | int64 | Нет | Число matching FileItem | SERVER_GENERATED |
| totalPages | int32 | Нет | Число страниц | SERVER_GENERATED |
| hasNext | boolean | Нет | Есть следующая | SERVER_GENERATED |
| hasPrevious | boolean | Нет | Есть предыдущая | SERVER_GENERATED |

Query page default0>=0; size default10>0, max не ограничен; folderId optional. Все CLIENT_SUPPLIED. Сортировка fixed descending capturedAt/uploadedAt/id. Нет page cursor/snapshot version.

## FileChecksumDto / ChecksumExistsResponse

| DTO.field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| FileChecksumDto.id | int64 | Нет | Logical file ID | SERVER_GENERATED |
| FileChecksumDto.originalFilename | string | Нет | Logical name | SERVER_AUTHORITATIVE |
| FileChecksumDto.checksum | string | Нет | StoredObject SHA-256 | SERVER_AUTHORITATIVE |
| ChecksumExistsResponse.existing | string[] | Нет, [] допустим | Unique normalized input hashes, имеющиеся в target | SERVER_GENERATED |
| ChecksumExistsResponse.missing | string[] | Нет, [] допустим | Unique normalized input hashes без target FileItem | SERVER_GENERATED |

[CONFIRMED] FileChecksumDto не содержит folderId, при одинаковом checksum в разных folders будут разные entries/IDs. Exists не возвращает IDs или metadata.

## Upload: поля вне JSON DTO

| Field | Type | Nullable | Semantics / validation | Ownership |
| --- | --- | --- | --- | --- |
| file | multipart binary part | Нет | Один nonempty файл; service100MiB | CLIENT_SUPPLIED |
| file.filename | multipart filename string | Возможен отсутствующий/null | Sanitizer fallback file; server может изменить имя | CLIENT_SUPPLIED |
| file Content-Type | MIME header | Не authoritative | Tika определяет MIME отдельно | CLIENT_SUPPLIED |
| folderId | request/multipart int64 | Да | null→server default по типу, иначе own folder | CLIENT_SUPPLIED |

Checksum/size/date/status не отдельные accepted upload fields. Успех возвращает FileItemDto200, created флага нет.

## File request DTO

| DTO.field | Type | Nullable | Semantics / validation | Ownership |
| --- | --- | --- | --- | --- |
| RenameFileRequest.originalName | string | Нет | Nonblank max255, затем trim/sanitize | CLIENT_SUPPLIED |
| MoveFileRequest.targetFolderId | int64 | Нет | @NotNull, собственная folder | CLIENT_SUPPLIED |
| CopyFileRequest.targetFolderId | int64 | Да | null→source folder, что затем конфликтует по checksum | CLIENT_SUPPLIED |
| CopyFileRequest.originalName | string | Да | max255; null/blank→source name; иначе trim/sanitize | CLIENT_SUPPLIED |
| ChecksumExistsRequest.folderId | int64 | Нет | @NotNull, own folder | CLIENT_SUPPLIED |
| ChecksumExistsRequest.checksums | string[] | Нет, не пуст | Raw length<=500, item nonblank64hex; duplicates/case допустимы | CLIENT_SUPPLIED |

Path id во всех file mutations — CLIENT_SUPPLIED int64 logical FileItem.id; отсутствие положительного @Min означает, что0/negative numeric ID обычно просто не находятся, а не имеют отдельный validation rule.

## Folder request DTO

| DTO.field | Type | Nullable | Semantics / validation | Ownership |
| --- | --- | --- | --- | --- |
| CreateFolderRequest.parentId | int64 | Да | null→ROOT; own ROOT/USER | CLIENT_SUPPLIED |
| CreateFolderRequest.name | string | Нет | Nonblank max255; trim; sibling uniqueness/reserved names | CLIENT_SUPPLIED |
| RenameFolderRequest.name | string | Нет | Nonblank max255; only USER | CLIENT_SUPPLIED |
| MoveFolderRequest.targetParentId | int64 | Нет | @NotNull; own ROOT/USER; no self/descendant | CLIENT_SUPPLIED |

Folder mutation path id — CLIENT_SUPPLIED int64. Delete folder body отсутствует.

## Auth request DTO

| DTO.field | Type | Nullable | Semantics / validation | Ownership |
| --- | --- | --- | --- | --- |
| RegisterRequest.email | string | Нет | Nonblank @Email; lowercased server; DTO без explicit max128 | CLIENT_SUPPLIED |
| RegisterRequest.password | string | Нет | Nonblank length4..20; не hash | CLIENT_SUPPLIED |
| LoginRequest.email | string | Нет | Nonblank @Email | CLIENT_SUPPLIED |
| LoginRequest.password | string | Нет | Nonblank length4..20 | CLIENT_SUPPLIED |
| RefreshRequest.refreshToken | string | Нет | Nonblank, issued refresh string | CLIENT_SUPPLIED |
| LogoutRequest.refreshToken | string | Нет | Nonblank; used logout и logout-others | CLIENT_SUPPLIED |
| PasswordResetConfirmRequest.password | string | Нет | Nonblank length4..20 | CLIENT_SUPPLIED |
| PasswordResetConfirmRequest.code | string | Нет | Nonblank email code | CLIENT_SUPPLIED |

Register displayName закомментирован, не request field. Query email reset request required nonblank без @Email; query code register confirm/reset page required без @NotBlank. Это отдельные CLIENT_SUPPLIED параметры, не DTO.

## Auth response DTO / UserDto

| DTO.field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| LoginResponse.accessToken | string | Нет при200 | JWT ACCESS20min | SERVER_GENERATED |
| LoginResponse.refreshToken | string | Нет при200 | JWT REFRESH7days | SERVER_GENERATED |
| RefreshResponse.accessToken | string | Нет при200 | Новый access; refresh не возвращается | SERVER_GENERATED |
| UserDto.id | int64 | Нет | Account ID | SERVER_GENERATED |
| UserDto.email | string | Нет | Login email | SERVER_AUTHORITATIVE |
| UserDto.displayName | string | Да | Профильное имя; update API501 | SERVER_AUTHORITATIVE |
| UserDto.enabled | boolean | Нет | Current DB flag | SERVER_AUTHORITATIVE |
| UserDto.banned | boolean | Нет | Current DB flag | SERVER_AUTHORITATIVE |
| UserDto.roles | string[] | Обычно нет, USER для register | USER/ADMIN, порядок Set не обещан | SERVER_AUTHORITATIVE |
| UserDto.createdAt | local datetime string | SQL допускает null | Account creation | SERVER_GENERATED |
| UserDto.lastUpdate | local datetime string | SQL допускает null | Account update | SERVER_GENERATED |
| UserDto.lastLoginAt | local datetime string | Да до login | Последний successful login | SERVER_GENERATED |

## ErrorResponse и простые responses

| Field | Type | Nullable | Semantics | Ownership |
| --- | --- | --- | --- | --- |
| ErrorResponse.id | UUID string | Нет у controlled error | Новый случайный error ID | SERVER_GENERATED |
| ErrorResponse.code | enum string | Нет | Код из таблицы03 | SERVER_GENERATED |
| ErrorResponse.message | string | Обычно нет | Human-readable текст, не стабильный parser key | SERVER_GENERATED |
| ErrorResponse.fieldErrors | map string→string | Да | Только validation details | SERVER_GENERATED |
| message (Map response) | string | Нет | Register/reset/logout/pings/501 | SERVER_GENERATED |

Registration confirmation200 — plain string, не {message}; DELETE204 — без JSON. Download — raw bytes с Content-Type и Content-Disposition. Uniform error schema для всех framework failures не гарантирована.

## Time/path/status и client-local данные

[CONFIRMED] LocalDateTime JSON означает local ISO datetime без timezone offset, например 2026-09-10T12:30:00; число fractional digits не закреплено. Не добавлять смысл UTC автоматически. Для JWT iat/exp используется JWT NumericDate; это другой time representation.

[CONFIRMED] Physical path, upload status, sync status, device ID и client-local identifiers не пересекают API. [DOCUMENTED] README-description предлагает локальные queue statuses. local MediaStore ID/content URI/path/scan state/retry counter относятся к CLIENT_LOCAL_ONLY с точки зрения server API; **их фактическое наличие и названия в Android не установлены**.

Источники: [21 DTO](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto), [FileItemMapper](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/FileItemMapper.java), [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [SQL schema](C:/projects/photo-cloud-server/src/main/resources/db/changelog/table).
