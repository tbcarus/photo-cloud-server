# PhotoCloud Server — сводка текущего состояния

PhotoCloud Server предоставляет персональное файловое облако через REST API `/api/v1`. Сервер хранит пользователей, логическую структуру папок и метаданные в PostgreSQL, а содержимое файлов — в локальной файловой системе. Целевой клиент — Android; доступны и другие HTTP-клиенты. Фактические алгоритмы Android не входят в серверный контракт.

## Стек и архитектура

Один Gradle-модуль и один процесс Spring Boot образуют слоистый монолит: controllers → services → JPA repositories. Java 17, Spring Boot 3.4.4, Spring MVC/Security/Data JPA/Validation, Hibernate, PostgreSQL, Liquibase; Gradle wrapper 8.13. JWT реализован JJWT 0.12.6, маппинг — MapStruct 1.6.3 и Lombok, MIME — Apache Tika 3.1.0, метаданные изображений — metadata-extractor 2.19.0. Почта использует JavaMail и Thymeleaf. API-документация доступна через springdoc-openapi 2.8.3.

Сервер обрабатывает загрузку, извлечение метаданных, копирование и отправку писем синхронно. Прикладных очередей, фоновых workers и scheduled cleanup нет.

## Данные и файлы

В модели семь JPA entities и восемь прикладных таблиц с учётом `user_roles`. `FileItem.id` — идентификатор логического файла для клиента. `StoredObject.id` — идентификатор записи о физических байтах; он не выдаётся как file ID. Папки существуют в БД и не зеркалируются каталогами на диске.

Физический объект располагается под `storage.root/users/{userId}/objects/{первые 2 hex}/{следующие 2 hex}/`; имя включает обработанное исходное имя, UUID и расширение. Rename и move изменяют только `FileItem`; copy создаёт новый `FileItem`, `StoredObject` и независимые байты.

Дубликат определяется по `(user, folder, SHA-256)`. Повторная загрузка в ту же папку возвращает существующий `FileItemDto` с HTTP 200, прежним именем и временем. Те же байты в другой папке создают новую физическую копию. Checksum вычисляется сервером. Pre-check и повтор upload проверяют логическую запись, а не сохранность её байтов.

Owner-delete удаляет все логические ссылки на `StoredObject`, затем объект БД, после commit — физический файл. Это hard delete; ожидания последней ссылки нет. При вручную созданной ссылке на чужой объект non-owner удаляет только свою запись. HTTP API создания таких общих ссылок отсутствует.

## Доступ

Login проверяет email/BCrypt-пароль и флаги `enabled`/`banned`. Access JWT живёт 20 минут и не хранится в БД. Refresh JWT живёт 7 дней, хранится целиком и отзывается флагом. Refresh выдаёт только новый access без ротации и продления refresh. Три logout-маршрута отзывают один, все или остальные refresh-токены пользователя; уже выданный access продолжает действовать до истечения.

Файлы и папки адресуются через owner-scoped lookup: отсутствующий и чужой ID дают 404. Роли USER/ADMIN существуют, но отдельных возможностей ADMIN и управления ролями через API нет. Reset пароля меняет хеш, не отзывая токены. Проверка флагов учётной записи повторно в access/refresh не выполняется.

## Возможности API

36 method/path mappings: 29 с прикладной реализацией и 7 заглушек 501. Реализованы регистрация/активация, login/refresh/logout, API восстановления пароля, чтение профиля, создание и организация папок, upload двумя маршрутами, list/get/download/rename/move/copy/delete файлов, полный список checksum и batch pre-check в одной папке.

Статусы соответствуют [15-feature-matrix.md](15-feature-matrix.md). PARTIAL: восстановление из email-ссылки (`SRV-AUTH-010`); роли и блокировка (`SRV-AUTH-015`); image metadata — JPEG/PNG dimensions, EXIF/GPS best effort (`SRV-MEDIA-002`); полный sync protocol (`SRV-SYNC-003`); DB/FS compensation (`SRV-STORAGE-004`); физическое завершение удаления (`SRV-FILE-011`); единый формат контролируемых ошибок (`SRV-API-002`); покрытие manual smoke (`SRV-OPS-006`). Video metadata/duration — NOT PRESENT (`SRV-MEDIA-004`).

Профиль: чтение — IMPLEMENTED (`SRV-PROFILE-001`); изменение — STUB (`SRV-PROFILE-002`); чтение/изменение settings — STUB (`SRV-PROFILE-003`); смена email, удаление account и avatar — NOT PRESENT (`SRV-PROFILE-004`).

Другие STUB: resend активации (`SRV-AUTH-011`), resend reset (`SRV-AUTH-012`), GET/POST reset page (`SRV-AUTH-013`), служебный email delete helper (`SRV-AUTH-020`). UNUSED: `deletedAt` (`SRV-FILE-012`), ограничитель email-запросов (`SRV-AUTH-014`), ADMIN/method-security annotations (`SRV-AUTH-016`), object lookup по checksum (`SRV-STORAGE-007`). Корзины, thumbnails, альбомов, тегов, поиска, sharing API, device/sync/upload sessions, chunk/resume upload и delta feed нет.

## Существенные ограничения контракта

Максимум файла в сервисе — 100 MiB; servlet multipart limits — 110MB; batch checksum — 500 входных элементов. Список файлов имеет fixed sort и неограниченный сверху `size`. Copy в исходную папку конфликтует; move при совпадении checksum может вернуть 500. Файловая очистка выполняется best effort. Общего формата для всех framework/IO-ошибок нет. Политики retention, backup и восстановления не заданы приложением. Полный реестр последствий находится в [рисках](16-risks.md).
