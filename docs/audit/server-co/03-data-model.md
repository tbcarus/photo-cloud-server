# Логическая модель данных

[CONFIRMED] 7 entities: User, RefreshToken, EmailRequest, Folder, FileItem, StoredObject, FileMetadata. user_roles — collection table User.roles, не восьмая entity. EmailContext и ExtractedFileMetadata не persistent. Все entity IDs — Long с IDENTITY; до insert null, после сохранения non-null.

В таблицах «nullable» отражает эффективную SQL-схему после миграции 14. Если JPA слабее, это отмечено отдельно; Java reference type сам по себе не гарантирует non-null. Источники: model/*.java, SQL 01–14.

## User → users

| Поле | Java type | Nullable | Default/формирование | Смысл |
| --- | --- | --- | --- | --- |
| id | Long | Нет | identity | PK |
| email | String | Нет | lowercased registration input | Login/username; SQL unique, 128 |
| password | String | Нет | BCrypt от input | Hash, SQL 128; в DTO отсутствует |
| displayName | String | Да | null на новом register | SQL 128, прежние first/last объединены SQL 09 |
| enabled | boolean | Нет | false; confirm → true | Подтверждённая регистрация |
| banned | boolean | Нет | false | Блокировка; изменяющий endpoint не найден |
| roles | Set<Role> | Коллекция | USER при register | EAGER ElementCollection, USER/ADMIN |
| createdAt | LocalDateTime | Да по SQL | @CreationTimestamp | Создание |
| lastUpdate | LocalDateTime | Да по SQL | @UpdateTimestamp | Изменение, в том числе login |
| lastLoginAt | LocalDateTime | Да | login → now | Последний успешный login |

[CONFIRMED] Производные: getUsername()=email; getAuthorities() → ROLE_USER/ROLE_ADMIN; isAccountNonLocked()=!banned. UserService.login() отдельно проверяет enabled/banned. Удаление пользователя через API отсутствует; SQL cascade не равен очистке файлов. JPA не описывает SQL 128/NOT NULL email/password — несоответствие строгости деклараций, а не доказанная ошибка schema validation.

## RefreshToken → refresh_token

| Поле | Java type | Nullable SQL | Default/формирование |
| --- | --- | --- | --- |
| id | Long | Нет | Identity PK |
| token | String | Нет | Подписанный refresh JWT; SQL TEXT с migration 08 |
| userName | String | Нет | email, SQL varchar(255), **не FK** |
| expires | LocalDateTime | Нет | JWT exp → systemDefault zone |
| revoked | boolean | Да в SQL | SQL false; builder false |
| revokedAt | LocalDateTime | Да | одиночный revoke → now; bulk revoke не заполняет |

[CONFIRMED] Несколько строк на email, новый UUID jti при login. Уникальность token в SQL не задана. Ротации и автоудаления нет. expires — сохранённое представление exp, refresh validation читает JWT exp, а не сравнивает колонку expires. RefreshTokenRepository ошибочно параметризован Integer при Long entity ID; вызываемые lookup — token/email.

## EmailRequest → email_requests

| Поле | Java type | Nullable SQL | Default/формирование |
| --- | --- | --- | --- |
| id | Long | Нет | Identity |
| code | String | Нет | UUID random string; unique, 255 |
| type | EmailRequestType | Нет | ACTIVATE / PASSWORD_RESET, STRING |
| used | boolean | Нет | false |
| user | User | Нет | LAZY ManyToOne, user_id → users |
| createdAt | LocalDateTime | Нет | @CreationTimestamp; SQL NOW() |

[CONFIRMED] isActive() означает createdAt + 3 дня > now и сам по себе **не учитывает used**. checkEmailRequest() дополнительно требует !used и совпадение type. Terminal usability: использован либо истёк; строка остаётся в БД. confirmRegistration() использует владельца кода. resetPassword() помечает used все PASSWORD_RESET rows пользователя за последние 3 дня. Repository также Integer вместо Long. delete() — пустой метод.

## Folder → folder

| Поле | Java type | Nullable | Default/формирование |
| --- | --- | --- | --- |
| id | Long | Нет | Identity |
| user | User | Нет | LAZY FK users |
| parent | Folder | Да | LAZY self-FK; root null |
| name | String | Нет | 255; trim в service |
| folderType | FolderType | Нет | ROOT/CAMERA/FILES/USER, STRING 20 |
| createdAt | LocalDateTime | Нет | @CreationTimestamp / SQL now |
| updatedAt | LocalDateTime | Нет | @UpdateTimestamp / SQL now |

[CONFIRMED] ROOT «root» создаётся лениво; Camera/Files — лениво при default upload нужного типа. До первого такого вызова пользователь может не иметь ни одной папки. USER создаётся в ROOT/USER. Системные папки нельзя rename/move/delete; CAMERA/FILES нельзя использовать родителями пользовательских папок. В ROOT имена Camera/Files зарезервированы. Одноуровневые имена уникальны case-insensitive; только ROOT имеет отдельный partial unique index по user. SQL запрещает self-parent, но не произвольный цикл, не связывает parent owner с child owner и не требует parent=null исключительно для ROOT.

[CONFIRMED] Children не хранятся коллекцией entity: repository ищет parent_id. Изменение содержимого файла/папки само по себе не обновляет folder.updatedAt; аннотация относится к изменению row Folder. Удаление только пустой USER в API.

## FileItem → file_item

| Поле | Java type | Nullable | Default/формирование |
| --- | --- | --- | --- |
| id | Long | Нет | Identity; клиентский media ID |
| user | User | Нет | LAZY FK users |
| folder | Folder | Нет | LAZY FK folder |
| storedObject | StoredObject | Нет | LAZY FK stored_object |
| checksum | String | Нет | 64; копия StoredObject.checksum на upload/copy |
| originalName | String | Нет | 255; sanitizer; wire-name originalFilename |
| capturedAt | LocalDateTime | Нет | Extracted Original/Digitized либо uploadedAt |
| uploadedAt | LocalDateTime | Нет | now при upload/copy, updatable=false; SQL now |
| deletedAt | LocalDateTime | Да | Не устанавливается current service |
| metadata | FileMetadata | Да | OneToOne inverse, cascade ALL, orphanRemoval |

[CONFIRMED] Unique(user_id, folder_id, checksum). Имена проверяются сервисом без SQL unique, кроме CAMERA, где конфликт имени допускается. Физическое содержание при rename/move не меняется. После hard delete row исчезает, состояние deletedAt в API не является tombstone.

[CONFIRMED] Вычисляемые DTO поля size/mimeType/checksum/fileType берутся из StoredObject; folderId из Folder. DB FileItem.checksum и отображаемый checksum могут разойтись при внешней правке: триггера согласования нет.

## StoredObject → stored_object

| Поле | Java type | Nullable | Default/формирование |
| --- | --- | --- | --- |
| id | Long | Нет | Identity |
| user | User | Нет | LAZY FK, владелец физических байтов |
| filePath | String | Нет | 1024; users/{userId}/objects/{h0h1}/{h2h3} |
| filename | String | Нет | 255; ограниченное исходное имя + underscore UUID + extension |
| fileExtension | String | Нет | 20; sanitized lower extension, может быть пустой строкой |
| checksum | String | Нет | SHA-256 lowercase 64 |
| size | Long | Нет | Подсчитанные байты |
| detectedMimeType | String | Нет | Tika, 100 |
| fileType | FileType | Нет | IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/OTHER, STRING 20 |
| createdAt | LocalDateTime | Нет | @CreationTimestamp / SQL now |

[CONFIRMED] Начиная с SQL 13 checksum внутри user не unique; path/filename тоже не SQL unique. Модель позволяет несколько FileItem на один StoredObject, в том числе другого пользователя. Current upload/copy создают собственные независимые объекты и такой sharing не создают.

## FileMetadata → file_metadata

| Поле | Java type | Nullable | Назначение |
| --- | --- | --- | --- |
| id | Long | Нет | Identity PK |
| fileItem | FileItem | Нет | LAZY unique FK → file_item |
| width / height | Integer | Да | Размеры JPEG/PNG в пикселях |
| durationSec | Integer | Да | Поле длительности; текущий extractor не устанавливает |
| cameraMake / cameraModel / lensModel | String | Да | EXIF строки |
| exposureTime | String | Да | getString EXIF; не гарантирован нормализованный формат |
| fNumber / focalLength | BigDecimal | Да | Rational → double → BigDecimal; SQL numeric(10,4) |
| iso | Integer | Да | EXIF ISO |
| latitude / longitude | BigDecimal | Да | GPS; SQL numeric(10,7) |

[CONFIRMED] Создаётся, только если hasMetadataFields() true; один capturedAt не создаёт metadata row. Видео и non-image дают пустой extracted result. Любая Exception в image extraction даёт WARN и пустые metadata. Copy переносит имеющиеся поля. SQL lengths/precision заданы в migration 10, но не продублированы @Column в FileMetadata.

## ER

~~~mermaid
erDiagram
    USERS ||--o{ USER_ROLES : roles
    USERS ||--o{ EMAIL_REQUESTS : codes
    USERS ||--o{ FOLDER : owns
    USERS ||--o{ FILE_ITEM : owns
    USERS ||--o{ STORED_OBJECT : owns
    FOLDER o|--o{ FOLDER : parent
    FOLDER ||--o{ FILE_ITEM : contains
    STORED_OBJECT ||--o{ FILE_ITEM : referenced_by
    FILE_ITEM ||--o| FILE_METADATA : metadata
    USERS {
        bigint id PK
        varchar email UK
        boolean enabled
        boolean banned
    }
    REFRESH_TOKEN {
        bigint id PK
        text token
        varchar user_name
        timestamp expires
        boolean revoked
    }
    FILE_ITEM {
        bigint id PK
        bigint user_id FK
        bigint folder_id FK
        bigint stored_object_id FK
        varchar checksum
    }
~~~

[CONFIRMED] REFRESH_TOKEN связан с users только логически через email; линия FK намеренно отсутствует.

## Отсутствующие сущности

[CONFIRMED] Отдельных Photo/Video/MediaFile, Upload, Sync, Device, Session, Album, Thumbnail, ProcessingState, Quota нет. MediaFile — историческая media_file таблица, удалённая SQL 10. TokenType и FileType — классификаторы, не сущности состояний. CLIENT_LOCAL_ONLY поля из README-description.md — [DOCUMENTED] идеи локального Android-индекса, не серверная схема.

