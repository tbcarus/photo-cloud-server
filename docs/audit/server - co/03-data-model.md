# 03. Логическая модель данных

Все поля ниже подтверждены entity-кодом [CONFIRMED]. Nullable и defaults различаются у Java/ORM и SQL; колонка «ограничение» разделяет эти источники. SQL-схема полностью развёрнута в отчёте 04. Общий пакет: [model](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model).

## User → users

Назначение: учётная запись и Spring Security UserDetails.

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | @Id IDENTITY; PK БД; null до persistence |
| email | String | JPA без nullable/length; SQL NOT NULL VARCHAR(128), UNIQUE; register lowercases |
| password | String | JPA без ограничений; SQL NOT NULL VARCHAR(128); BCrypt hash |
| displayName | String | Nullable; SQL VARCHAR(128); DTO регистрации не принимает |
| enabled | boolean | Java false; SQL NOT NULL DEFAULT FALSE; после ACTIVATE true |
| banned | boolean | Java false; SQL NOT NULL DEFAULT FALSE; login отклоняет banned |
| roles | Set<Role> | EAGER @ElementCollection; user_roles; register задаёт USER |
| createdAt | LocalDateTime | @CreationTimestamp; SQL nullable |
| lastUpdate | LocalDateTime | @UpdateTimestamp; SQL nullable; обновляется и при login |
| lastLoginAt | LocalDateTime | Nullable; устанавливается UserService.login |

Связи: roles — коллекция значений; обратных collections файлов/папок/кодов нет. getUsername() вычисляет email; getAuthorities() создаёт ROLE_USER/ROLE_ADMIN; isAccountNonLocked() = !banned. Жизненный цикл: регистрация disabled → подтверждение enabled; API ban/unban/delete/profile update не реализован.

## RefreshToken → refresh_token

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | @Id IDENTITY |
| token | String | SQL TEXT NOT NULL; полный подписанный JWT; JPA без TEXT mapping |
| userName | String | SQL VARCHAR(255) NOT NULL; email, не User relation |
| expires | LocalDateTime | SQL NOT NULL; копия exp JWT в timezone JVM |
| revoked | boolean | Java false; SQL nullable DEFAULT FALSE |
| revokedAt | LocalDateTime | Nullable; single revoke задаёт now, bulk revoke не задаёт |

Связи/FK: отсутствуют. Unique token не установлен. Новый login создаёт новую строку; refresh только читает и выдаёт access; expiry не удаляет строку. Проверка срока в refresh использует JWT exp, а не expires БД. AccessToken entity отсутствует. Репозиторий ошибочно типизирован ID Integer, хотя entity ID Long.

## EmailRequest → email_requests

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | @Id IDENTITY; SQL GENERATED ALWAYS |
| code | String | UUID string; SQL NOT NULL UNIQUE VARCHAR(255); JPA ограничения не повторены |
| type | EmailRequestType | STRING: ACTIVATE / PASSWORD_RESET; SQL NOT NULL |
| used | boolean | Java false; SQL NOT NULL DEFAULT FALSE |
| user | User | LAZY ManyToOne, @OnDelete CASCADE; SQL user_id NOT NULL FK |
| createdAt | LocalDateTime | CreationTimestamp, updatable=false; SQL NOT NULL DEFAULT NOW() |

isActive() проверяет только createdAt + 3 days > now; used и type не участвуют. isExpired() = !isActive(). Сервис отдельно проверяет used/type/expiry; ACTIVATE включает пользователя, PASSWORD_RESET меняет hash и использует актуальные reset-коды. Поля expiresAt нет. EmailRequestRepository имеет ID Integer вместо Long.

## Folder → folder

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | IDENTITY PK |
| user | User | LAZY ManyToOne; NOT NULL |
| parent | Folder | LAZY ManyToOne; nullable; ROOT без parent |
| name | String | NOT NULL, length 255; service trim |
| folderType | FolderType | NOT NULL STRING length 20; ROOT/CAMERA/FILES/USER |
| createdAt | LocalDateTime | NOT NULL CreationTimestamp, updatable=false; SQL now |
| updatedAt | LocalDateTime | NOT NULL UpdateTimestamp; SQL now |

Collections children нет: repository запрашивает прямых потомков. Сервис создаёт ROOT=root лениво, затем Camera/Files при default upload; USER создаётся только под ROOT/USER. Имена siblings уникальны case-insensitive по SQL expression indexes; отдельный partial unique запрещает второй ROOT. Тип неизменяем через API; системные папки нельзя rename/move/delete. У USER terminal state — физическое удаление пустой строки.

[INCONSISTENCY] «parent null только у ROOT» обеспечивается создающим кодом, но не SQL CHECK. CHECK запрещает только self-parent; FK не гарантирует принадлежность parent тому же user или отсутствие длинных циклов.

## StoredObject → stored_object

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | IDENTITY PK |
| user | User | LAZY ManyToOne NOT NULL; владелец байтов |
| filePath | String | NOT NULL length 1024; относительный каталог |
| filename | String | NOT NULL length 255; физическое имя |
| fileExtension | String | NOT NULL length 20; может быть пустой строкой |
| checksum | String | NOT NULL length 64; серверный lowercase SHA-256 |
| size | Long | NOT NULL; фактически прочитанные bytes |
| detectedMimeType | String | NOT NULL length 100; Tika |
| fileType | FileType | NOT NULL STRING length 20 |
| createdAt | LocalDateTime | NOT NULL CreationTimestamp; SQL now |

Checksum indexed, но не unique после migration 13; путь/filename не unique в текущей БД. Входящего API обновления физических полей нет. StoredObject может иметь несколько FileItem по модели, но normal upload/copy создают новый объект. При owner delete удаляются все references, затем объект, затем bytes.

## FileItem → file_item

| Поле | Java type | Ограничение / default / смысл |
| --- | --- | --- |
| id | Long | IDENTITY PK; ID, используемый клиентом во всех file endpoints |
| user | User | LAZY ManyToOne NOT NULL; владелец логической записи |
| folder | Folder | LAZY ManyToOne NOT NULL |
| storedObject | StoredObject | LAZY ManyToOne NOT NULL |
| checksum | String | NOT NULL length 64; копия StoredObject.checksum |
| originalName | String | NOT NULL length 255; логическое имя |
| capturedAt | LocalDateTime | NOT NULL; EXIF либо uploadedAt |
| uploadedAt | LocalDateTime | NOT NULL, updatable=false; service now; SQL default now |
| deletedAt | LocalDateTime | Nullable, не заполняется текущим API |
| metadata | FileMetadata | Optional LAZY OneToOne mappedBy; Cascade.ALL + orphanRemoval |

Unique (user,folder,checksum) обеспечен migration 14. Равенство владельцев FileItem/Folder/StoredObject не обеспечено композитными FK. Rename меняет originalName, move — folder, copy создаёт другой FileItem и StoredObject. uploadedAt copy новый, capturedAt copy наследуется. Флаг uploaded/status отсутствует: успешность представлена наличием строки и bytes, а не отдельным полем.

## FileMetadata → file_metadata

| Поле | Java type | Nullable и смысл |
| --- | --- | --- |
| id | Long | PK IDENTITY |
| fileItem | FileItem | NOT NULL UNIQUE FK; владеющая сторона OneToOne |
| width | Integer | Nullable, ширина JPEG/PNG |
| height | Integer | Nullable, высота JPEG/PNG |
| durationSec | Integer | Nullable; текущий extractor не задаёт |
| cameraMake | String | Nullable; EXIF Make |
| cameraModel | String | Nullable; EXIF Model |
| lensModel | String | Nullable; EXIF LensModel |
| exposureTime | String | Nullable; EXIF строка, не числовая длительность |
| fNumber | BigDecimal | Nullable; EXIF rational |
| iso | Integer | Nullable; EXIF ISO |
| focalLength | BigDecimal | Nullable; EXIF rational |
| latitude | BigDecimal | Nullable; GPS |
| longitude | BigDecimal | Nullable; GPS |

Строка создаётся только если ExtractedFileMetadata.hasMetadataFields() true. capturedAt в этом условии не участвует и хранится в FileItem. Copy дублирует metadata, delete FileItem каскадно удаляет. SQL precision/length отличаются от неуточнённых JPA defaults, см. 04.

## Типы и отсутствующие модели

[CONFIRMED] FileType: IMAGE/VIDEO/AUDIO по MIME prefix; DOCUMENT по 10 MIME литералам (PDF, legacy/OOXML Word/Excel/PowerPoint, plain text, CSV, RTF); ARCHIVE по ZIP/RAR/7z/gzip/tar списку; OTHER иначе. MIME comparison lowercased, списка запрещённых типов нет.

[CONFIRMED] Role: USER, ADMIN. TokenType содержит имя claim token_type и значения ACCESS/REFRESH. EmailContext — временный DTO почты (from/to/subject/attachment/fromDisplayName/displayName/template/context), не @Entity. attachment и display-name-поля не применяются MimeMessageHelper.

[CONFIRMED] В model/migrations нет сущностей Photo, Video, Album, Thumbnail, Checksum, UploadSession, MediaStatus, SyncSession, Device, server HTTP Session, share/ACL/settings. Их нельзя выводить из README-планов или названий enum.

## ER

~~~mermaid
erDiagram
  users ||--o{ user_roles : roles
  users ||--o{ email_requests : owns
  users ||--o{ folder : owns
  folder o|--o{ folder : parent
  users ||--o{ stored_object : owns_bytes
  users ||--o{ file_item : owns_entry
  folder ||--o{ file_item : contains
  stored_object ||--o{ file_item : referenced_by
  file_item ||--o| file_metadata : metadata
  users {
    bigint id PK
    varchar email UK
    varchar password
    boolean enabled
    boolean banned
  }
  refresh_token {
    bigint id PK
    text token
    varchar user_name "email; no FK"
    timestamp expires
    boolean revoked
  }
  file_item {
    bigint id PK
    bigint user_id FK
    bigint folder_id FK
    bigint stored_object_id FK
    varchar checksum "UK user-folder-checksum"
    varchar original_name
    timestamp captured_at
    timestamp uploaded_at
    timestamp deleted_at
  }
~~~

RefreshToken намеренно не соединён FK-линией с users. Cardinality StoredObject 1:N описывает допустимую модель, а не фактическое sharing API.
