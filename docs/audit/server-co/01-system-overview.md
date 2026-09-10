# Система в целом

[CONFIRMED] Область реализации — персональное многопользовательское файловое хранилище с HTTP API. Сервер хранит произвольные непустые файлы в пределах лимита; фото/видео — категории, а не единственные разрешённые типы. Источники: FileController.java, FileItemService.uploadFile(), FileType.fromMimeType().

[DOCUMENTED] README.md описывает личный сервер, README-description.md — автозагрузку камеры Android. [CONFIRMED] RootController имеет connectivity checks с комментарием об Android, ChecksumSyncService реализует pre-check. Код самого Android-клиента, его локальная БД и сетевые настройки здесь отсутствуют; их фактическое использование endpoint-ов неизвестно. HTTP smoke files и Swagger — дополнительные способы обращения. Browser recovery предусмотрен, но page endpoint — 501.

## Фактические границы

~~~text
Android / HTTP client / Swagger
 → HttpLoggingFilter
 → Spring Security + JwtAuthenticationFilter
 → Auth/Register/Password/User/Root/File/FolderController
 → UserService / JwtService / EmailRequestService
   / FileItemService / FolderService / ChecksumSyncService
 → Spring Data repositories → Hibernate/JPA → PostgreSQL
 → FileUtils + StoragePathResolver + java.nio.file.Files → storage.root
 → Tika + DrewFileMetadataExtractor → временно вычисленные MIME/EXIF
 → EmailService + Thymeleaf + JavaMailSender → SMTP
~~~

[CONFIRMED] Request выполняется синхронно до ответа; собственных очередей и фонового подтверждения нет. SMTP отправляется в request thread. API не выдаёт presigned URL и не перенаправляет upload в сторонний storage. Источники: PhotoCloudServerApplication.java, service/*.java, build.gradle.

## Пользовательские сценарии

| Сценарий | Фактическая ответственность сервера | Граница |
| --- | --- | --- |
| Регистрация | Нижний регистр email, BCrypt, disabled USER, ACTIVATE code, попытка письма | Доставка письма не равна успешному HTTP-ответу |
| Login/refresh/logout | Пароль, выпуск JWT, сохранение и отзыв refresh | Клиентское безопасное хранение токенов вне репозитория |
| Папки | ROOT/Camera/Files лениво, CRUD пользовательских папок с ограничениями | Пользовательские пути не становятся physical paths |
| Автозагрузка | Batch checksum check и однократный multipart upload | Scan телефона, локальные статусы, расписание/retry — не реализованы сервером |
| Работа с файлами | List/card/download/rename/move/copy/hard delete | Нет overwrite, версии, trash или события удаления |
| Профиль | Чтение полей текущего User | Edit/settings 501 |
| Сброс пароля | Создание code и JSON confirm, смена hash | HTML-страница из письма не реализована |

Все строки [CONFIRMED]: соответствующие controller и service; заявленный Android-сценарий отдельно [DOCUMENTED]: README-description.md.

## Источники истины и происхождение данных

| Данные | Вход/генерация | Постоянное хранение | Authoritative смысл |
| --- | --- | --- | --- |
| email, password | Клиент; email lowercased, пароль хешируется | users.email/password | Аккаунт и hash определяет сервер |
| роли, enabled, banned | USER/false/false на регистрации; enabled меняет confirm | users/user_roles | Серверная авторизация; admin-edit API нет |
| IDs | PostgreSQL identity | Все entities | Серверные идентификаторы Long, не ID телефона |
| имя файла | multipart filename или rename/copy body | file_item.original_name | После sanitation/усечения — серверное логическое имя |
| байты | Клиент multipart | Файловая система | Сохранённое содержимое; integrity check на download не выполняется |
| checksum/size/MIME/type | Сервер читает байты | stored_object; checksum также file_item | Pre-check доверяет file_item.checksum, DTO — stored_object.checksum |
| folderId | Клиент либо серверный default | file_item.folder_id | Принадлежность и текущая папка — сервер |
| capturedAt | EXIF Original → Digitized, иначе uploadedAt | file_item.captured_at | Сервер выбирает значение из клиентского содержимого, достоверность EXIF не проверяет |
| uploadedAt | LocalDateTime.now() | file_item.uploaded_at | Момент обработки после чтения temp, до final move/commit |
| EXIF/GPS | Из клиентского файла | file_metadata optional | Не независимо проверенные факты о снимке |
| токены | Серверные JWT | refresh_token хранит refresh; access не хранится | Подпись/exp access, БД revoked для refresh |
| дерево | API папок | folder | Истина логической структуры |
| page counters, existing/missing | Запросы БД | Не сохраняются | Снимок на момент запроса; не sync checkpoint |
| MIME/EXIF промежуточные объекты, temp | Вычисляются при upload | Temp до move/cleanup | Нет durable upload state |

[CONFIRMED] Источники: UserService.java, JwtService.java, FileItemService.java, FileItemMapper.java, ChecksumSyncService.java, DrewFileMetadataExtractor.java.

## Постоянное и временное

[CONFIRMED] Постоянны users, roles, refresh, email codes, folder, file_item, stored_object, file_metadata и final-файлы. Refresh/email rows автоматически не удаляются. Не найдены Device, UploadSession, SyncSession, Album, Tag, quota, thumbnail и processing-job entity.

[CONFIRMED] Временные данные: request wrappers, security context текущего запроса, upload temp, MessageDigest/буфер 8192 байт, DTO/страницы и email template context. В servlet layer multipart threshold=0; temp приложения — отдельная стадия после получения MultipartFile. [INFERRED] Одновременно могут существовать servlet temp и temp приложения; точный расход диска зависит от контейнера. Источники: application.yml, FileUtils.java, FileItemService.java, HttpLoggingFilter.java.

[INFERRED] Несколько инстансов требуют согласованного доступа к одной БД и физическому storage: путь хранится относительно root, маршрутизации к конкретному узлу нет. Фактическая deployment-топология неизвестна.

