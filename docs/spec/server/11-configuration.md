# Runtime configuration

Значения ниже — поставляемые defaults и placeholders, а не прочитанные настройки работающего процесса. Секреты и JWT не копируются. Шесть обязательных external values без project fallback: DB_URL, DB_USER, DB_PASSWORD, JWT_KEY, SMTP_USERNAME, SMTP_PASSWORD. STORAGE_ROOT и STORAGE_TEMP_DIR имеют defaults.

| Property | Источник/default | Обязательность | Назначение / consumer |
| --- | --- | --- | --- |
| spring.application.name | photo-cloud-server | Задан | Идентификация Boot application |
| spring.datasource.url | ${DB_URL}, без default | External required | JPA/Liquibase PostgreSQL |
| spring.datasource.username | ${DB_USER} | External required | DB credentials |
| spring.datasource.password | ${DB_PASSWORD} | External required; secret | DB credentials |
| spring.datasource.driver-class-name | org.postgresql.Driver | Задан | JDBC driver |
| spring.liquibase.change-log | classpath:db/changelog/db.changelog-master.yml | Задан | Migration master |
| spring.liquibase.enabled | true | Задан | Выполнение миграций при startup |
| spring.jpa.show-sql | true | Задан | SQL output |
| spring.jpa.open-in-view | false | Задан | Нет ORM session на весь MVC response |
| spring.jpa.hibernate.ddl-auto | validate | Задан | Проверка schema, не генерация DDL |
| spring.thymeleaf.prefix | classpath:/templates/ | Задан | Boot property; custom engine создаётся MailConfig |
| spring.thymeleaf.suffix | .html | Задан | Аналогично; custom resolver имеет свои literals |
| spring.mail.host | smtp.yandex.ru | Задан, override возможен | MailConfig @Value |
| spring.mail.port |465 | Задан | MailConfig |
| spring.mail.username | ${SMTP_USERNAME} | External required | SMTP login |
| spring.mail.password | ${SMTP_PASSWORD} | External required; secret | SMTP auth |
| spring.mail.mail-from | ${SMTP_USERNAME}@yandex.ru | Задан | EmailService; также инъекция MailConfig без дальнейшего использования |
| spring.servlet.multipart.max-file-size |110MB | Задан | Servlet multipart limit |
| spring.servlet.multipart.max-request-size |110MB | Задан | Весь multipart request вместе с overhead |
| spring.servlet.multipart.file-size-threshold |0 | Задан | Multipart staging на диск |
| server.servlet.context-path |/ | Задан | API root |
| server.port |8080 | Задан | HTTP listener |
| token.signing.key | ${JWT_KEY} | External required; secret | JwtService Base64 decode HMAC key |
| storage.root | ${STORAGE_ROOT:storage} | Default storage | StoragePathResolver; absolute от cwd |
| storage.max-file-size-bytes |104857600 | Также Java default | FileUtils streaming limit100MiB |
| storage.temp-dir | ${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp} | Default root env/tmp | FileItemService temp path |
| storage.physical-filename.original-name-max-length |80 | Также Java default | StorageKeyGenerator/FilenameSanitizer |
| sync.checksum-exists.max-batch-size |500 | Также Java default | ChecksumSyncService до lowercase/dedup |
| logging.level.liquibase |info | Задан | Migration logs |
| logging.level.ru.tbcarus.photocloudserver |INFO | Задан | Включает HTTP bodies и email logs |


## Java literals и границы override

| Параметр | Значение / механизм |
| --- | --- |
| Access / refresh TTL | 20 минут /7 дней в JwtService; YAML TTL отсутствует |
| Email expiry / reset invalidation window | 3 дня; значения в EmailRequest и ConfigUtil |
| Password validation | 4..20 символов |
| Default folders | root, Camera, Files |
| File copy buffer | 8192 bytes |
| Logical name / physical component / extension | 255 /255 /20 Java characters; исключение long extension описано в storage |
| List defaults | page0, size10; верхняя граница size отсутствует |
| HTTP log text | Первые1000 characters; размер response cache этим не ограничен |
| BCrypt | Constructor default, explicit cost не настроен |
| MailConfig | protocol smtp; auth, starttls.enable, ssl.enable, debug=true |
| Thymeleaf mail resolver | /templates/, .html, HTML, UTF-8, cache=false |
| Security | STATELESS, CSRF off, permitAll в Java |

`spring.mail.mail-from` вычисляется как `${SMTP_USERNAME}@yandex.ru`. Sender domain задан конфигурацией; он не равен независимо зафиксированному literal address. Mail debug установлен Java-кодом. Timeout/retry SMTP явно не настроены.

Temp default обращается к переменной STORAGE_ROOT, а не к итоговому property storage.root. Поэтому отдельный override storage.root не гарантирует тот же override temp-dir. StorageProperties/ChecksumExistsProperties не имеют Bean Validation конфигурационных лимитов; наличие значений не означает предварительную проверку всех допустимых диапазонов.

## Profiles и окружение

Поставлены application.yml и test profile application-test.yml. Test profile использует Liquibase/validate, open-in-view=false, show-sql=false, app/liquibase WARN, тестовые mail/JWT значения. Integration tests динамически задают PostgreSQLContainer postgres:16-alpine, temp storage и service file limit1024 bytes; servlet110MB остаётся отдельно.

Локальные `.env.local` и `.env.docker` располагаются внутри resources и исключены gitignore. Их значения не входят в эту спецификацию. Автоматический loader/import этих файлов не описан production configuration; способ передачи environment внешним launcher остаётся OPEN. Наличие файла не равно факту его загрузки. При включении таких файлов в build resources возможна упаковка чувствительных значений (RISK-SRV-032).

Java17 и Gradle8.13 заданы сборкой. В поставляемой конфигурации нет явной timezone JVM, TLS/proxy/CORS policy, quota, DB pool/server/SMTP timeout policy, multipart location, log retention, metric exporter или job schedule. Framework defaults и внешняя инфраструктура не считаются настройками приложения. Реальные volume mounts, HTTPS и applied schema остаются OPEN-SRV-025–028.
