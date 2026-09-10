# Конфигурация и эксплуатационные параметры

[CONFIRMED] Просмотрены build.gradle, settings.gradle, gradle-wrapper.properties, application.yml, application-test.yml, @Value, @ConfigurationProperties и кодовые константы. Реальные секреты и окружение процесса не выгружались. ENV ниже — имена placeholders, не их фактические значения. Файлы .env* и env.properties исключены .gitignore; явного dotenv-loader/config import в коде нет.

## Application properties

| Имя | Источник / default | Обязательность | Назначение / потребитель |
| --- | --- | --- | --- |
| spring.application.name | application.yml: photo-cloud-server | Задан | Boot application identity |
| spring.datasource.url | DB_URL, без default | Да для запуска с БД | JDBC datasource |
| spring.datasource.username | DB_USER, без default | Да в текущей config | JDBC credentials |
| spring.datasource.password | DB_PASSWORD, без default | Да в текущей config | JDBC credentials, секрет |
| spring.datasource.driver-class-name | org.postgresql.Driver | Задан | PostgreSQL |
| spring.liquibase.change-log | classpath:db/changelog/db.changelog-master.yml | Задан | includeAll SQL table folder |
| spring.liquibase.enabled | true | Задан | Миграции на startup |
| spring.jpa.show-sql | true; test false | Не секрет | SQL logging |
| spring.jpa.open-in-view | false | Задан | Persistence context не удерживается для view |
| spring.jpa.hibernate.ddl-auto | validate | Задан | Проверка mappings, не создание схемы вместо Liquibase |
| spring.thymeleaf.prefix / suffix | classpath:/templates/ / .html | Заданы | Дополнительно есть собственный template bean |
| spring.mail.host | smtp.yandex.ru | Задан | MailConfig.getJavaMailSender |
| spring.mail.port | 465 | Задан | SMTP |
| spring.mail.username | SMTP_USERNAME, без default | Да | SMTP username |
| spring.mail.password | SMTP_PASSWORD, без default | Да | SMTP secret |
| spring.mail.mail-from | SMTP_USERNAME + @yandex.ru | Да через placeholder | EmailService From |
| server.servlet.context-path | / | Задан | Base servlet context |
| server.port | 8080 | Задан | HTTP listener |
| token.signing.key | JWT_KEY, без default | Да | Base64 HMAC key; JwtService |
| storage.root | STORAGE_ROOT или storage | Да, default YAML | StoragePathResolver |
| storage.temp-dir | STORAGE_TEMP_DIR или <STORAGE_ROOT/default storage>/tmp | Да, default YAML | FileItemService.createTempFile |
| storage.max-file-size-bytes | 104857600 в YAML и StorageProperties | Default есть | Stream limit, 100 MiB |
| storage.physical-filename.original-name-max-length | 80 в YAML и class | Default есть | StorageKeyGenerator/FilenameSanitizer |
| spring.servlet.multipart.max-file-size | 110MB | Задан | Servlet file limit |
| spring.servlet.multipart.max-request-size | 110MB | Задан | Весь multipart request включая overhead |
| spring.servlet.multipart.file-size-threshold | 0 | Задан | Servlet disk buffering threshold |
| sync.checksum-exists.max-batch-size | 500 в YAML и ChecksumExistsProperties | Default есть | Проверка исходного list size до dedup |
| logging.level.liquibase | info; test warn | Задан | Liquibase logs |
| logging.level.ru.tbcarus.photocloudserver | INFO; test warn | Задан | HTTP, email, storage, metadata logger |

[CONFIRMED] StorageProperties.root/tempDir не имеют Java default; working defaults приходят YAML. Properties classes не аннотированы validation/min constraints. Переопределённые отрицательные/некорректные значения могут приводить к ошибкам/отказам, не fail-fast validation contract.

## Кодовые константы, не ENV-настройки

| Параметр | Значение / источник | Использование |
| --- | --- | --- |
| Access lifetime | 20 * 60 * 1000 ms, JwtService | exp |
| Refresh lifetime | 7 * 24 * 60 * 60 * 1000 ms | exp |
| Email expiry | 3 days, EmailRequest.DEFAULT_EXPIRED_DAYS | isActive/isExpired |
| Reset-code consume window | 3 days, ConfigUtil.DEFAULT_EXPIRED_DAYS | resetPassword query |
| Request attempt limit | 3, checkAndGenerateCode literals / constants | [UNUSED] Нет вызова из current endpoints |
| Password size | 4..20 DTO annotations | Register/Login/ResetConfirm |
| File/folder logical length | 255 | DTO/service/SQL |
| Physical component / extension | 255 / 20, FilenameSanitizer | Filename construction |
| ROOT/Camera/Files names | FolderService constants | Lazy folders/reserved names |
| File default page/size | 0/10, FileController | Нет configurable upper cap |
| Stream buffer | 8192, FileUtils | SHA/stream |
| HTTP body log text cap | 1000 characters, HttpLoggingFilter | Усечение **после** caching, не memory bound |
| SMTP protocol/auth/starttls/ssl/debug | smtp / true / true / true / true, MailConfig | Жёстко записываются в JavaMail properties |
| Template prefix/suffix/mode/charset/cache | /templates/ / .html / HTML / UTF-8 / false | MailConfig custom resolver |
| Mail display placeholders | FROM_DISPLAY_NAME / DISPLAY_NAME | EmailService context, фактическое персональное name отдельно |
| Email URL base | request scheme/serverName/port/contextPath | Нет fixed public origin property |
| BCrypt | new BCryptPasswordEncoder() | Strength явно не передан; default библиотеки |
| JVM timezone | ZoneId.systemDefault()/LocalDateTime.now | Captured/refresh times, email expiry |

[INCONSISTENCY] README пример SMTP_USERNAME выглядит полным email, а mail-from дописывает @yandex.ru; если задать полный адрес, получится другой неверный sender. Пример JWT_KEY в README не является подтверждённым корректным Base64 HMAC key. Значения из примеров здесь не копируются.

## Test profile

[CONFIRMED] application-test.yml переопределяет show-sql=false, mail localhost:2525 с тестовыми credentials/sender, test signing key, log levels warn; Liquibase и ddl validation остаются включены. Ключ в отчёт не перенесён.

[CONFIRMED] AbstractIntegrationTest @ActiveProfiles(test), @DynamicPropertySource: PostgreSQLContainer postgres:16-alpine выдаёт JDBC URL/user/password; storage.root — Files.createTempDirectory, temp-dir root/tmp, max-file-size-bytes=1024. Это настройки тестовой БД, не версия production PostgreSQL и не лимит клиентского production API.

## Сборка и окружение

[CONFIRMED] Java toolchain 17; Boot 3.4.4, dependency-management plugin 1.1.7; Gradle distribution 8.13, networkTimeout=10000 ms в wrapper относится к скачиванию distribution, **не HTTP API**. Group ru.tbcarus, version 0.0.1-SNAPSHOT. PostgreSQL driver объявлен одновременно runtimeOnly без explicit version и implementation 42.7.8. Разрешённый dependency graph runtime не снимался.

[CONFIRMED] src/main содержит один application.yml; отдельные production/local YAML profiles, Dockerfile/compose/CI manifests в tracked tree не найдены. [DOCUMENTED] README упоминает Docker и .env.local/.env.docker, но это не доказательство наличия deployment definition или автоматического чтения .env.

[CONFIRMED] Собственные API connection/read/write timeouts, Hikari pool overrides, SMTP connection/read/write timeouts, TLS server settings, CORS origins, proxy/forwarded headers strategy, metrics exporters, log retention/rotation, storage quotas и backup schedule не заданы. Framework/environment defaults не выданы за явно настроенные параметры. Внешний reverse proxy и production ENV неизвестны.

