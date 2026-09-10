# 01. Система в целом

## Назначение и границы

[DOCUMENTED] README описывает личный сервер для собственного облачного хранения фотографий и других файлов. README-description описывает автозагрузку камеры Android и варианты локальной очереди. Наличие конкретной версии Android-клиента, его фактические запросы и алгоритм sync этим репозиторием не подтверждены.

[CONFIRMED] Фактические функции: учёт пользователей/email-кодов/refresh-токенов; авторизованные операции с логическими папками, файлами и байтами; определение типа и metadata; checksum pre-check. Сервер не сканирует телефон, не управляет его локальной БД, сетью, разрешениями Android или выбором файлов. Входящие запросы выполняются синхронно, в пределах HTTP-запроса.

[CONFIRMED] Клиентские поверхности: JSON REST API и multipart upload; raw download; строковое подтверждение регистрации; Swagger/OpenAPI от подключённой библиотеки; HTML письма. Браузерный password-reset flow объявлен, но его page endpoints возвращают JSON 501. Отдельного веб-интерфейса файлового облака нет.

## Фактическая схема

~~~text
Android / иной HTTP client / Swagger UI
  → HttpLoggingFilter
  → Spring Security + JwtAuthenticationFilter
      → JwtService → RefreshTokenRepository (refresh/logout)
      → UserService → UserRepository (principal из БД)
  → controllers /api/v1
      → Auth/Register/Password → UserService + EmailRequestService
          → PostgreSQL users, user_roles, refresh_token, email_requests
          → EmailService → Thymeleaf → JavaMailSender → SMTP
      → UserController → UserDto из principal
      → FolderController → FolderService → FolderRepository
      → FileController → FileItemService
          → FileItemRepository / StoredObjectRepository → PostgreSQL
          → FolderService → FolderRepository
          → FileUtils + Tika + Drew extractor
          → storage.temp-dir → storage.root/users/.../objects/...
      → FileController → ChecksumSyncService → FileItemRepository
~~~

[CONFIRMED] Модель данных содержит 7 JPA entities и 8 прикладных таблиц, считая user_roles. Отдельных Photo, Video, Device, Session, Upload, Sync сущностей нет. Тип файла — FileType, а не отдельная таблица.

## Основные пользовательские сценарии

| Сценарий | Фактический результат | Источник |
| --- | --- | --- |
| [CONFIRMED] Register → email confirmation | Disabled User, ACTIVATE code, затем enabled=true и used=true | UserService.register; EmailRequestService.confirmRegistration |
| [CONFIRMED] Login → profile | Два JWT; lastLoginAt обновлён; профиль без password | UserService.login; UserController.getProfile |
| [CONFIRMED] Refresh/logout | Новый access либо отзыв одного/всех/остальных refresh | JwtService |
| [PARTIAL] Восстановление пароля | API confirm работает; ссылка письма ведёт на 501 page | EmailService.getEmailContext; PasswordController |
| [CONFIRMED] Просмотр/организация | Страница файлов; прямые потомки папки; create/rename/move/delete пустой USER-папки | FileController; FolderService |
| [CONFIRMED] Загрузка | Temp → hash/size/MIME → folder → duplicate/name → final → DB → FileItemDto | FileItemService.uploadFile |
| [CONFIRMED] Получение/удаление | Карточка, download, hard delete | FileItemService |
| [CONFIRMED] Copy | Независимая физическая копия в другую папку при отсутствии checksum | FileItemService.copyFileForCurrentUser |
| [PARTIAL] Sync | Только наличие checksum в выбранной папке; отдельного согласования изменений нет | ChecksumSyncService |

## Источники истины и ownership

| Данные | Вход/генерация | Авторитет и хранение |
| --- | --- | --- |
| [CONFIRMED] Email, password | Клиент JSON; email lowercased, password BCrypt | users; email — username; plaintext не сохраняется в users |
| [CONFIRMED] Роли, enabled, banned | USER/false/false при регистрации; enabled после ACTIVATE | users/user_roles; API изменения ролей/блокировки отсутствует |
| [CONFIRMED] File bytes | Клиент multipart | Файловая система; сервер не изменяет содержимое upload |
| [CONFIRMED] Original filename | Клиент; заменяются опасные символы и ограничивается длина | FileItem.originalName; серверное нормализованное имя возвращается в originalFilename |
| [CONFIRMED] Folder membership | folderId от клиента либо server default | FileItem.folder; владелец устанавливается из principal |
| [CONFIRMED] SHA-256, size, MIME, FileType | Сервер читает поток/файл | StoredObject; checksum также дублируется в FileItem |
| [CONFIRMED] capturedAt | EXIF Original/Digitized; иначе uploadedAt | FileItem; LocalDateTime без timezone |
| [CONFIRMED] uploadedAt, IDs, UUID filename | Сервер/БД | Постоянно |
| [CONFIRMED] width/height/EXIF/GPS | Из байтов клиента через серверный extractor | FileMetadata, все поля optional |
| [CONFIRMED] existing/missing | Клиентские hashes сопоставляются с file_item | Временный ответ; не журнал sync |
| [CONFIRMED] JWT access | Сервер подписывает | На сервере не сохраняется; клиент получает строку |
| [CONFIRMED] JWT refresh, revoked | Сервер и logout | refresh_token; нет FK к users |
| [CONFIRMED] Email code, used, createdAt | UUID сервера, состояние в БД | email_requests; expiry вычисляется |
| [DOCUMENTED] Локальные состояния сканирования/очереди | README-description предлагает клиентские статусы | Сервер их не принимает и не хранит |

[INCONSISTENCY] Прочитанная из FileItemDto checksum берётся из StoredObject, но exists и DB unique используют FileItem.checksum. Нормальный upload/copy задаёт одинаковые значения; автоматического контроля равенства после произвольной записи в БД нет.

## Постоянные и временные данные

[CONFIRMED] Постоянны строки БД, физические final-файлы, refresh и email-коды. Удаление файла не создаёт tombstone; журналы хранятся только средствами логирования окружения, файловая retention-политика не задана.

[CONFIRMED] Временны multipart staging контейнера, upload temp, буфер 8192 байта, результат Tika/extractor, principal одного запроса, pagination и checksum partition. Temp ожидается удалить после завершения, но durable cleanup job отсутствует.

[INFERRED] Корректное логическое наличие по БД и доступность физических байтов — разные свойства. Метаданные могут существовать без файла; проверка checksum этого не обнаружит. API не определяет процедуру восстановления такой ситуации.

## Источники

[Controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [Services](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [README-description.md](C:/projects/photo-cloud-server/README-description.md), [application.yml](C:/projects/photo-cloud-server/src/main/resources/application.yml).
