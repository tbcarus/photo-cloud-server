# PhotoCloud Server: технический аудит As-Is

Дата аудита: 2026-09-10. Срез: commit c2e9593a30270fddd2fb80d3f2d6ff0c533d2d37 (2026-06-22, cleanup). На старте git status --short не показывал изменений.

## Границы и доказательность

Аудит исходного кода, SQL-миграций, конфигурации, тестов и документации. Сервер не запускался; действующая БД, SMTP и содержимое пользовательского storage не опрашивались. Gradle-тесты не запускались: задание разрешает создавать только отчёты, а integration suite выполняет миграции, TRUNCATE и файловые операции. Наличие теста не означает успешный прогон в этом аудите. Фактическая версия развёрнутой БД, работоспособность deployment и эксплуатационная нагрузка неизвестны.

Обозначения во всём пакете:

| Метка | Значение |
| --- | --- |
| [CONFIRMED] | Подтверждено исполняемым кодом; для SQL/config/test явно указано происхождение. Не означает runtime-проверку |
| [DOCUMENTED] | Заявлено документацией, полностью кодом не подтверждено |
| [INFERRED] | Вывод из структуры/порядка операций, требующий дополнительных условий |
| [PARTIAL] | Есть реализация части функции |
| [UNUSED] | Объявление есть, вызывающий production-код не найден в этом репозитории |
| [INCONSISTENCY] | Проверяемое расхождение компонентов или документации |
| [RISK] | Оценка возможного последствия; не утверждение о произошедшем инциденте |

Источники имеют приоритет: код → миграции → конфигурация → тесты → API-документы → README → комментарии. Отрицательные результаты поиска ограничены данным checkout. Идеи развития не включены в описание фактического поведения.

## Что представляет собой система

[CONFIRMED] Один Spring Boot backend с синхронным REST API /api/v1. Java 17, Spring Boot 3.4.4, Gradle wrapper 8.13, Spring MVC/Security/Data JPA/Mail/Validation, PostgreSQL, Liquibase, JJWT 0.12.6, MapStruct 1.6.3, Tika core 3.1.0 и metadata-extractor 2.19.0. Источники: build.gradle, gradle-wrapper.properties, PhotoCloudServerApplication.java.

[CONFIRMED] Метаданные находятся в реляционной БД, байты — на локальной файловой системе. Логическая запись FileItem отделена от StoredObject; дерево Folder не повторяется на диске. Физический путь строится из storage.root, user ID, первых четырёх hex-символов checksum и имени с UUID. Источники: FileItem.java, StoredObject.java, Folder.java, StorageKeyGenerator.java, StoragePathResolver.java.

[CONFIRMED] 7 контроллеров объявляют 36 пар HTTP method/path. 29 содержат рабочую логику, 7 возвращают 501. 7 JPA entities соответствуют 8 прикладным таблицам, включая user_roles; в changelog 14 SQL-файлов с 16 changesets. Источники: controller/*.java, model/*.java, db.changelog-master.yml и SQL 01–14.

[CONFIRMED] Основные функции: регистрация/подтверждение email, login, refresh, три варианта logout, сброс пароля через JSON API, чтение профиля, папки, upload, list, card, download, rename/move/copy/delete, полный список checksum и batch existence check. Авторизация файлов и папок проверяет владельца логической записи; чужой ID обычно даёт тот же 404, что отсутствующий.

## Ключевые правила для клиентов

[CONFIRMED] Access JWT действует 20 минут, refresh JWT — 7 дней. Refresh хранится открытой строкой в БД, отзывается сервером, при refresh не ротируется. Logout отзывает refresh, но уже выпущенный access сохраняет возможность аутентификации до истечения срока. Источники: JwtService.java, JwtAuthenticationFilter.java.

[CONFIRMED] SHA-256 вычисляет сервер по байтам. Дубль — user + folder + checksum. Повторный upload в ту же папку возвращает 200 и существующий FileItemDto, сохраняя прежнее имя/ID. В другую папку те же байты создают новый StoredObject. Copy также физически копирует файл; в исходную папку copy конфликтует даже с новым именем. Источник: FileItemService.java.

[CONFIRMED] Existence check проверяет записи БД, а не доступность байтов. Отдельного upload-complete, resume/chunk API, версии синхронизации, журнала удалений и устройств нет. Файл удаляется физически после удаления БД; корзина не реализована. Источники: ChecksumSyncService.java, FileItemRepository.java, FileItemService.java, контроллеры.

## Готовность и незавершённость

[INFERRED] Это функциональное ядро личного файлового хранилища, пригодность которого к эксплуатации нельзя подтвердить одним чтением кода. Оценка «production-ready» и процент готовности не выводятся из числа методов. Есть 88 методов @Test, но нет обнаруженного покрытия аварий процесса и реальных конкурентных гонок.

[PARTIAL] Email recovery: JSON-сброс работает по коду, однако письмо ведёт на /auth/password/reset/page, который возвращает 501. Повторная отправка писем, изменение профиля/settings также заглушки. Видео сохраняется и скачивается, но текущий extractor обрабатывает только image/*; durationSec не заполняется. Sharing поддержан формой связей и веткой delete, но API выдачи доступа отсутствует.

## Наиболее существенные риски

| ID | Оценка | Проверяемое основание |
| --- | --- | --- |
| SEC-01 | [RISK] HIGH: секреты в логах | HttpLoggingFilter записывает JSON bodies; EmailService — контекст и HTML с кодом |
| SEC-02 | [RISK] HIGH: блокировка не прекращает действующий доступ | enabled/banned проверяются в login, но не в refresh и JWT filter |
| DATA-01 | [RISK] HIGH: upgrade может уничтожить прежние метаданные | SQL 10 делает DROP TABLE media_file CASCADE без переноса данных |
| STORE-01 | [RISK] HIGH: рассогласование disk/DB при аварии | нет общей транзакции; cleanup только в обработчиках исключений |
| API-01 | [RISK] MEDIUM: move с занятым checksum → 500 | move проверяет имя, SQL 14 ограничивает checksum, handler возвращает 500 |
| OPS-01 | [RISK] HIGH: память на download | HttpLoggingFilter оборачивает любой ответ в ContentCachingResponseWrapper |
| AUTH-01 | [RISK] MEDIUM: браузерный recovery не завершён | ссылка письма ведёт на 501, resend тоже 501 |

Полные основания, условия и последствия: [реестр рисков](16-known-gaps-and-risks.md). Карта исходников и проверка полноты: [18-code-map.md](18-code-map.md).

## Навигация

Основной пакет: 01 обзор; 02 архитектура; 03 логическая модель; 04 БД; 05 API; 06 security; 07 storage; 08 процессы; 09 состояния; 10 ошибки; 11 конфигурация; 12 фоновые процессы; 13 тесты; 14 observability; 15 feature matrix; 16 риски; 17 вопросы; 18 карта кода.

Самодостаточный пакет Android: [client-handoff/01-api-contract-effective.md](client-handoff/01-api-contract-effective.md) и семь соседних документов. Он не требует чтения серверных исходников.
