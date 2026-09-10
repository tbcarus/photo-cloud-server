# 11. Конфигурация

## Источники и ограничения

[CONFIRMED] Прочитаны tracked application.yml, application-test.yml, build.gradle, settings.gradle, Gradle wrapper, Java @Value/@ConfigurationProperties и literals. В resources найдены локальные .env.local и .env.docker, но доступ к их содержимому запрещён окружением. Их значения и полнота переменных не проверены; обход ограничения не выполнялся. Никакие секреты не воспроизводятся.

[CONFIRMED] README описывает .env запуск, .gitignore исключает .env* и env.properties. В tracked коде нет spring.config.import этих файлов, dotenv dependency или loader; присутствие .env само по себе не доказывает загрузку в Spring. IDE/external launcher может передавать environment, его фактические значения неизвестны.

## Runtime application.yml

Источник всех строк раздела — [application.yml](C:/projects/photo-cloud-server/src/main/resources/application.yml). Обязательность означает отсутствие project fallback, а не измеренное состояние процесса.

| Property | Источник/default | Обязательность | Назначение / consumer |
| --- | --- | --- | --- |
| [CONFIRMED] spring.application.name | photo-cloud-server | Задан | Идентификация Boot application |
| [CONFIRMED] spring.datasource.url | ${DB_URL}, без default | External required | JPA/Liquibase PostgreSQL |
| [CONFIRMED] spring.datasource.username | ${DB_USER} | External required | DB credentials |
| [CONFIRMED] spring.datasource.password | ${DB_PASSWORD} | External required; secret | DB credentials |
| [CONFIRMED] spring.datasource.driver-class-name | org.postgresql.Driver | Задан | JDBC driver |
| [CONFIRMED] spring.liquibase.change-log | classpath:db/changelog/db.changelog-master.yml | Задан | Migration master |
| [CONFIRMED] spring.liquibase.enabled | true | Задан | Выполнение миграций при startup |
| [CONFIRMED] spring.jpa.show-sql | true | Задан | SQL output |
| [CONFIRMED] spring.jpa.open-in-view | false | Задан | Нет ORM session на весь MVC response |
| [CONFIRMED] spring.jpa.hibernate.ddl-auto | validate | Задан | Проверка schema, не генерация DDL |
| [CONFIRMED] spring.thymeleaf.prefix | classpath:/templates/ | Задан | Boot property; custom engine создаётся MailConfig |
| [CONFIRMED] spring.thymeleaf.suffix | .html | Задан | Аналогично; custom resolver имеет свои literals |
| [CONFIRMED] spring.mail.host | smtp.yandex.ru | Задан, override возможен | MailConfig @Value |
| [CONFIRMED] spring.mail.port |465 | Задан | MailConfig |
| [CONFIRMED] spring.mail.username | ${SMTP_USERNAME} | External required | SMTP login |
| [CONFIRMED] spring.mail.password | ${SMTP_PASSWORD} | External required; secret | SMTP auth |
| [CONFIRMED] spring.mail.mail-from | Literal sender address, значение не копируется | Задан | EmailService; также инъекция MailConfig без дальнейшего использования |
| [CONFIRMED] spring.servlet.multipart.max-file-size |110MB | Задан | Servlet multipart limit |
| [CONFIRMED] spring.servlet.multipart.max-request-size |110MB | Задан | Весь multipart request вместе с overhead |
| [CONFIRMED] spring.servlet.multipart.file-size-threshold |0 | Задан | Multipart staging на диск |
| [CONFIRMED] server.servlet.context-path |/ | Задан | API root |
| [CONFIRMED] server.port |8080 | Задан | HTTP listener |
| [CONFIRMED] token.signing.key | ${JWT_KEY} | External required; secret | JwtService Base64 decode HMAC key |
| [CONFIRMED] storage.root | ${STORAGE_ROOT:storage} | Default storage | StoragePathResolver; absolute от cwd |
| [CONFIRMED] storage.max-file-size-bytes |104857600 | Также Java default | FileUtils streaming limit100MiB |
| [CONFIRMED] storage.temp-dir | ${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp} | Default root env/tmp | FileItemService temp path |
| [CONFIRMED] storage.physical-filename.original-name-max-length |80 | Также Java default | StorageKeyGenerator/FilenameSanitizer |
| [CONFIRMED] sync.checksum-exists.max-batch-size |500 | Также Java default | ChecksumSyncService до lowercase/dedup |
| [CONFIRMED] logging.level.liquibase |info | Задан | Migration logs |
| [CONFIRMED] logging.level.ru.tbcarus.photocloudserver |INFO | Задан | Включает HTTP bodies и email logs |

[INCONSISTENCY] temp-dir default ссылается на переменную STORAGE_ROOT, а не на итоговое property storage.root. Если root переопределить другим механизмом (например argument --storage.root), temp-dir не обязан следовать этому override. Java StorageProperties.root/tempDir собственных defaults не имеют. Properties beans не помечены @Validated: отрицательные/неразумные лимиты заранее не проверяются.

## Java literals, которые не являются внешними настройками

| Значение | Где | Как влияет |
| --- | --- | --- |
| [CONFIRMED] Access lifetime20min, refresh7days | JwtService.expirationTime/refreshExpirationTime | Нельзя изменить только YAML без изменения кода |
| [CONFIRMED] Email expiry3days | EmailRequest.DEFAULT_EXPIRED_DAYS | isActive/isExpired |
| [CONFIRMED] Reset invalidation3days | ConfigUtil.DEFAULT_EXPIRED_DAYS | EmailRequestService.resetPassword |
| [UNUSED] ACTIVE_REQUESTS_MAX=3 в двух классах | EmailRequest, ConfigUtil | Не управляет active flows |
| [UNUSED] checkAndGenerateCode literals count>=3 /minusDays3 | EmailRequestService | Не вызывается register/forgot |
| [CONFIRMED] Password length4..20 | Login/Register/PasswordResetConfirmRequest | Bean Validation |
| [CONFIRMED] Names max255; physical component255; extension20 | DTO, FolderService, FilenameSanitizer, entities | Limits не полностью эквивалентны byte limits filesystem |
| [CONFIRMED] File buffer8192 | FileUtils.BUFFER_SIZE | Streaming hash/write |
| [CONFIRMED] List page0,size10 | FileController.getUserFiles | Нет upper bound |
| [CONFIRMED] root/Camera/Files | FolderService constants | Системные имена и reserved names |
| [CONFIRMED] HTTP logged text limit1000 | HttpLoggingFilter.MAX_BODY_LENGTH | Только truncation вывода, не cache bytes |
| [CONFIRMED] Bearer prefix | JwtAuthenticationFilter | Case-sensitive "Bearer " |
| [CONFIRMED] BCrypt default constructor | EncoderConfig | Explicit cost не задан |
| [CONFIRMED] SMTP protocol smtp; auth/starttls/debug/ssl=true | MailConfig.getJavaMailSender | Hardcoded mail properties |
| [CONFIRMED] Template prefix /templates/, suffix .html, HTML, UTF-8, cache=false | MailConfig.emailTemplateResolver | Собственный resolver |
| [CONFIRMED] Email targets | EmailService.getEmailContext | Request scheme/serverName/port/context + controller path + code |
| [CONFIRMED] CSRF off, STATELESS, permitAll paths | SecurityConfig | Security rules в коде |

## Test profile

Источник: [application-test.yml](C:/projects/photo-cloud-server/src/test/resources/application-test.yml), [AbstractIntegrationTest](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/AbstractIntegrationTest.java).

[CONFIRMED] @ActiveProfiles("test") включает Liquibase и validate; show-sql=false, open-in-view=false. SMTP localhost:2525, тестовые username/password/from и signing key заданы literals (не перенесены в аудит). Logging liquibase/package warn. Других tracked application-{profile}.yml нет.

[CONFIRMED] @DynamicPropertySource задаёт datasource URL/username/password/driver из PostgreSQLContainer postgres:16-alpine. root — Files.createTempDirectory("photo-cloud-server-it-"), temp=root/tmp, service max1024 bytes. Servlet110MB наследуется; integration limit test2048 bytes проверяет service threshold, а не реальный multipart110MB.

[CONFIRMED] Unit FileItemServiceTest задаёт root @TempDir, temp=root/tmp, max1024. ChecksumSyncServiceTest проверяет configs3 и2; production max500 отдельно покрывается integration input501.

## Build / окружение

[CONFIRMED] build.gradle Java toolchain17, Boot3.4.4, dependency-management1.1.7, Maven Central; wrapper Gradle8.13, networkTimeout10000ms, validateDistributionUrl=true. Это timeout wrapper download, не HTTP API/SMTP. Test task useJUnitPlatform и showStandardStreams=true. Отдельных CI workflows, Dockerfile/compose, deployment manifests в tracked дереве не найдено; Docker используется Testcontainers и упоминается документацией.

[CONFIRMED] Явно не настроены: DB schema/catalog, pool limits/timeouts, server HTTP timeouts, SMTP connect/read/write timeout, servlet multipart location, server TLS, proxy forwarded headers, CORS origins, metric/tracing exporters, log retention/file destination, quotas, periodic jobs, max folders/tree depth. Framework defaults не объявляются здесь собственными параметрами проекта.

[INFERRED] system timezone JVM влияет на LocalDateTime.now, exp→DB conversion и EXIF timestamps. В YAML/Java startup фиксированная timezone не задана. Europe/Moscow окружения аудита не доказывает timezone production JVM.
