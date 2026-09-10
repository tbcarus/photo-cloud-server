# 11. Configuration

> **Секреты в этот отчёт не копируются.** Файлы `src/main/resources/.env.local` и `.env.docker` содержат реальные значения; ниже перечислены только имена переменных.

---

## 1. Источники конфигурации

**[CONFIRMED]**

| Источник | Файл | Комментарий |
| --- | --- | --- |
| Основной | `src/main/resources/application.yml` | единственный профиль-независимый файл |
| Тестовый | `src/test/resources/application-test.yml` | активируется `@ActiveProfiles("test")` |
| Переменные окружения | `${...}` в `application.yml` | 6 обязательных + 2 опциональные |
| `.env`-файлы | `src/main/resources/.env.local`, `.env.docker` | **[RISK]** лежат в `resources`, то есть попадают в classpath и в jar |
| Динамические (тесты) | `AbstractIntegrationTest.registerProperties()` | datasource из Testcontainers + storage-пути |
| `@ConfigurationProperties` | `StorageProperties` (`storage.*`), `ChecksumExistsProperties` (`sync.checksum-exists.*`) | типобезопасные |
| `@Value` | `JwtService` (`token.signing.key`), `MailConfig` (5 значений), `EmailService` (`spring.mail.mail-from`) | |
| Хардкод в Java | TTL токенов, сроки email-кодов, имена системных папок | см. §7 |

**[CONFIRMED]** `.gitignore` содержит `.env*`, поэтому сами файлы не отслеживаются git — но они физически присутствуют в рабочей копии внутри `src/main/resources/`. **[RISK]** при сборке jar каталог `resources` целиком попадает в артефакт: если такие файлы окажутся в сборке, секреты уедут вместе с ней. Формально Spring их не читает (поддержки `.env` в Spring Boot нет), но артефакт всё равно будет их содержать.

**[CONFIRMED]** Spring-профили: единственный используемый — `test`. Профилей `dev`/`prod`/`docker` в коде нет; `application-*.yml` для них отсутствуют, хотя `README.md` упоминает «Docker (окружения `.env.local`, `.env.docker`)».

---

## 2. Переменные окружения

**[CONFIRMED]** Все обращения `${VAR}` в `application.yml`:

| Переменная | Обязательна | Default | Назначение | Где используется |
| --- | --- | --- | --- | --- |
| `DB_URL` | **да** | нет | JDBC URL PostgreSQL | `spring.datasource.url` |
| `DB_USER` | **да** | нет | пользователь БД | `spring.datasource.username` |
| `DB_PASSWORD` | **да** | нет | пароль БД | `spring.datasource.password` |
| `JWT_KEY` | **да** | нет | Base64-ключ подписи HMAC | `token.signing.key` → `JwtService` |
| `SMTP_USERNAME` | **да** | нет | логин SMTP; также используется для построения `mail-from` | `spring.mail.username`, `spring.mail.mail-from` |
| `SMTP_PASSWORD` | **да** | нет | пароль SMTP | `spring.mail.password` |
| `STORAGE_ROOT` | нет | `storage` | корень файлового хранилища | `storage.root` |
| `STORAGE_TEMP_DIR` | нет | `${STORAGE_ROOT:storage}/tmp` | каталог временных файлов | `storage.temp-dir` |

**[CONFIRMED][RISK]** Отсутствие любой из 6 обязательных переменных приводит к падению контекста при старте (`Could not resolve placeholder`) — fail-fast, но без внятного сообщения о том, какую переменную нужно задать. Документации по ним, кроме примера в `README.md`, нет.

**[CONFIRMED][INCONSISTENCY]** `spring.mail.mail-from: ${SMTP_USERNAME}@yandex.ru` — домен `yandex.ru` **захардкожен** в конфигурации. При смене почтового провайдера правится не только `spring.mail.host`, но и это выражение. Сравните: `.env.local` содержит `SMTP_USERNAME` как логин без домена.

---

## 3. Spring / инфраструктура

| Ключ | Значение | Обязателен | Назначение |
| --- | --- | --- | --- |
| `spring.application.name` | `photo-cloud-server` | нет | имя приложения |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` | да | драйвер |
| `spring.liquibase.change-log` | `classpath:db/changelog/db.changelog-master.yml` | да | master changelog |
| `spring.liquibase.enabled` | `true` | нет | миграции при каждом старте |
| `spring.jpa.show-sql` | **`true`** | нет | **[RISK]** SQL в stdout в production-конфиге |
| `spring.jpa.open-in-view` | `false` | нет | правильно: запрещает ленивую загрузку в контроллере |
| `spring.jpa.hibernate.ddl-auto` | `validate` | нет | схема не изменяется Hibernate |
| `spring.thymeleaf.prefix` | `classpath:/templates/` | нет | шаблоны |
| `spring.thymeleaf.suffix` | `.html` | нет | |
| `server.servlet.context-path` | `/` | нет | |
| `server.port` | `8080` | нет | HTTPS не настроен |

**[CONFIRMED]** Настройки пула соединений (HikariCP) не заданы — используются значения по умолчанию (max-pool-size 10). Для операций, которые держат соединение во время файловых операций, это может быть узким местом, хотя `FileItemService` специально минимизирует время удержания транзакции.

---

## 4. Multipart / лимиты загрузки

| Ключ | Значение | Назначение |
| --- | --- | --- |
| `spring.servlet.multipart.max-file-size` | `110MB` | лимит одного файла на уровне контейнера |
| `spring.servlet.multipart.max-request-size` | `110MB` | лимит всего запроса |
| `spring.servlet.multipart.file-size-threshold` | `0` | **всё** пишется на диск, ничего не буферизуется в памяти |

**[CONFIRMED]** `location` для multipart-temp не задан — используется системный temp-каталог, а не `storage.temp-dir`.

---

## 5. Storage (`StorageProperties`, prefix `storage`)

| Ключ | Default в yml | Default в Java | Тип | Назначение | Где используется |
| --- | --- | --- | --- | --- | --- |
| `storage.root` | `${STORAGE_ROOT:storage}` | `null` | `String` | корень хранилища | `StoragePathResolver.resolve()` |
| `storage.max-file-size-bytes` | `104857600` | `104857600L` | `long` | лимит при стриминге (~100 MiB) | `FileItemService.writeToTempFile()` |
| `storage.temp-dir` | `${STORAGE_TEMP_DIR:${STORAGE_ROOT:storage}/tmp}` | `null` | `String` | временные файлы | `FileItemService.createTempFile()` |
| `storage.physical-filename.original-name-max-length` | `80` | `80` | `int` | длина префикса исходного имени в физическом имени | `StorageKeyGenerator.generateFilename()` |

**[CONFIRMED][RISK]** `StorageProperties` не помечен `@Validated`, поля не имеют `@NotBlank`/`@Positive`. При `storage.root=""` приложение стартует и упадёт при первой загрузке файла.

---

## 6. Sync (`ChecksumExistsProperties`, prefix `sync.checksum-exists`)

| Ключ | Default yml | Default Java | Назначение |
| --- | --- | --- | --- |
| `sync.checksum-exists.max-batch-size` | `500` | `500` | максимум checksum в одном запросе `POST /files/checksums/exists` |

**[CONFIRMED]** Проверяется в `ChecksumSyncService.checkExisting()` до всех остальных действий; превышение → `IllegalArgumentException` → `400`.

---

## 7. JWT

| Параметр | Значение | Конфигурируем? | Где |
| --- | --- | --- | --- |
| `token.signing.key` | `${JWT_KEY}` (Base64) | **да** | `JwtService.jwtSigningKey` (`@Value`) |
| Access token TTL | **20 минут** (`20 * 60 * 1000`) | **нет — хардкод** | `JwtService.expirationTime` (`private final long`) |
| Refresh token TTL | **7 дней** (`7 * 24 * 60 * 60 * 1000`) | **нет — хардкод** | `JwtService.refreshExpirationTime` |
| Алгоритм | HMAC-SHA (выбирается JJWT по длине ключа) | нет | `Keys.hmacShaKeyFor()` |
| Issuer / audience | не задаются | — | — |

**[CONFIRMED][RISK]** Изменение сроков жизни токенов требует пересборки приложения. `TODO.txt` п.7 фиксирует намерение задать другие значения (access 2 часа, refresh 2 месяца) — сейчас не реализовано.

---

## 8. Mail

| Ключ | Значение | Назначение |
| --- | --- | --- |
| `spring.mail.host` | `smtp.yandex.ru` | **захардкожен** |
| `spring.mail.port` | `465` | SMTPS |
| `spring.mail.username` | `${SMTP_USERNAME}` | |
| `spring.mail.password` | `${SMTP_PASSWORD}` | |
| `spring.mail.mail-from` | `${SMTP_USERNAME}@yandex.ru` | **нестандартный ключ** (не из `spring.mail.*` схемы Spring Boot) |

**[CONFIRMED]** `MailConfig.getJavaMailSender()` дополнительно задаёт в коде:

```java
mail.transport.protocol = smtp
mail.smtp.auth          = true
mail.smtp.starttls.enable = true
mail.smtp.ssl.enable    = true
mail.debug              = true      ← [RISK] полный SMTP-диалог в stdout
```

**[CONFIRMED][INCONSISTENCY]** Одновременно включены `starttls.enable` и `ssl.enable` на порту 465. Порт 465 — implicit TLS (SMTPS), для него нужен только `ssl.enable`; STARTTLS относится к порту 587. Конфигурация работает (JavaMail игнорирует STARTTLS при implicit SSL), но противоречива.

**[CONFIRMED][RISK]** `mail.debug=true` захардкожен в Java — отключить конфигурацией нельзя.

**[CONFIRMED]** Таймауты (`mail.smtp.connectiontimeout`, `timeout`, `writetimeout`) не заданы — при зависании SMTP-сервера поток обработки HTTP-запроса регистрации может блокироваться надолго.

---

## 9. Logging

| Ключ | Значение | Комментарий |
| --- | --- | --- |
| `logging.level.liquibase` | `info` | |
| `logging.level.ru.tbcarus.photocloudserver` | `INFO` | **[RISK]** активирует `HttpLoggingFilter` с телами запросов/ответов |

**[CONFIRMED]** Не настроены: `logging.file.*` (запись в файл), pattern, ротация, appenders, structured/JSON-логи. Логи идут только в stdout со стандартным форматом Spring Boot. `logback-spring.xml` в проекте нет.

**[CONFIRMED]** Тестовый профиль понижает уровни: `liquibase: warn`, `ru.tbcarus.photocloudserver: warn` — то есть в тестах `HttpLoggingFilter` молчит (тесты используют собственное логирование с маскированием).

---

## 10. CORS

**[CONFIRMED]** Конфигурации нет ни в `application.yml`, ни в коде. См. `06-auth-security.md` §12.

---

## 11. Тестовая конфигурация

`src/test/resources/application-test.yml`:

| Ключ | Значение | Комментарий |
| --- | --- | --- |
| `spring.liquibase.*` | тот же changelog | схема идентична production |
| `spring.jpa.show-sql` | `false` | |
| `spring.jpa.open-in-view` | `false` | |
| `spring.jpa.hibernate.ddl-auto` | `validate` | |
| `spring.mail.*` | `localhost:2525`, `test`/`test`, `test@example.com` | SMTP-сервера нет — отправка упадёт и будет проглочена |
| `token.signing.key` | тестовый Base64-ключ (64 байта) | **не production-секрет**, лежит в репозитории — приемлемо для тестов |
| `logging.level.*` | `warn` | |

**[CONFIRMED]** Отсутствующие в файле значения приходят из `AbstractIntegrationTest.registerProperties()`:

| Ключ | Источник |
| --- | --- |
| `spring.datasource.url/username/password/driver-class-name` | Testcontainers `postgres:16-alpine` |
| `storage.root` | временный каталог `photo-cloud-server-it-*` |
| `storage.temp-dir` | `<temp>/tmp` |
| `storage.max-file-size-bytes` | **`1024`** — сознательно занижен, чтобы тестировать лимит на малых файлах |

---

## 12. Захардкоженные значения (не конфигурируемы)

**[CONFIRMED]** Сводка того, что нельзя изменить без пересборки:

| Значение | Где | Комментарий |
| --- | --- | --- |
| Access token TTL = 20 мин | `JwtService.expirationTime` | |
| Refresh token TTL = 7 дней | `JwtService.refreshExpirationTime` | |
| Срок жизни email-кода = 3 дня | `EmailRequest.DEFAULT_EXPIRED_DAYS`, `ConfigUtil.DEFAULT_EXPIRED_DAYS`, литерал в `checkAndGenerateCode()` | тройное дублирование |
| Лимит email-запросов = 3 | `EmailRequest.ACTIVE_REQUESTS_MAX`, `ConfigUtil.ACTIVE_REQUESTS_MAX`, литерал | метод не вызывается |
| Имена системных папок `root`, `Camera`, `Files` | `FolderService` | на них завязаны upload-правила |
| Максимальная длина имени папки = 255 | `FolderService.normalizeName()` | |
| Максимальная длина `originalName` = 255 | `FileItemService.normalizeOriginalName()` | |
| Максимальная длина расширения = 20 | `FilenameSanitizer.MAX_EXTENSION_LENGTH` | |
| Максимальная длина компонента пути = 255 | `FilenameSanitizer.MAX_COMPONENT_LENGTH` | |
| Размер буфера копирования = 8192 | `FileUtils.BUFFER_SIZE` | |
| Алгоритм хеша = SHA-256 | `FileUtils` | |
| BCrypt strength = 10 (default) | `EncoderConfig` | |
| Сортировка списка файлов | `FileController.getUserFiles()` | клиент не может изменить |
| `page` default = 0, `size` default = 10 | `FileController` | верхней границы нет |
| Максимальная длина тела в логе = 1000 | `HttpLoggingFilter.MAX_BODY_LENGTH` | |
| ANSI-цвета логов | `HttpLoggingFilter` | |
| MIME → расширение (8 пар) | `FilenameSanitizer.extensionFromMimeType()` | |
| Списки MIME документов и архивов | `FileType` | |
| Шардинг по 2+2 символа checksum | `StorageKeyGenerator.generateFilePath()` | |
| `mail.debug=true`, SSL/TLS-флаги | `MailConfig` | |
| SMTP-домен `@yandex.ru` в `mail-from` | `application.yml` | |

---

## 13. Развёртывание

**[CONFIRMED]** В репозитории **отсутствуют**:

- `Dockerfile`, `docker-compose.yml`, `.dockerignore`;
- каталог `.github` (CI/CD);
- systemd-unit, скрипты запуска;
- Kubernetes-манифесты, Helm-чарты;
- документация по развёртыванию (кроме примера переменных в `README.md`);
- `HELP.md` — стандартная заглушка Spring Initializr со ссылками на документацию (и добавлена в `.gitignore`).

**[INFERRED]** Предполагаемый способ запуска — `./gradlew bootRun` или `java -jar` с заданными переменными окружения, при внешнем PostgreSQL и внешнем reverse-proxy для TLS.

---

## 14. Сводная таблица всех конфигурационных параметров

| # | Параметр | Источник | Default | Обязателен | Назначение |
| --- | --- | --- | --- | --- | --- |
| 1 | `DB_URL` | env | — | ✅ | JDBC URL |
| 2 | `DB_USER` | env | — | ✅ | пользователь БД |
| 3 | `DB_PASSWORD` | env | — | ✅ | пароль БД |
| 4 | `JWT_KEY` | env | — | ✅ | ключ подписи JWT |
| 5 | `SMTP_USERNAME` | env | — | ✅ | логин SMTP |
| 6 | `SMTP_PASSWORD` | env | — | ✅ | пароль SMTP |
| 7 | `STORAGE_ROOT` | env | `storage` | ❌ | корень хранилища |
| 8 | `STORAGE_TEMP_DIR` | env | `<root>/tmp` | ❌ | temp-каталог |
| 9 | `server.port` | yml | `8080` | ❌ | порт |
| 10 | `server.servlet.context-path` | yml | `/` | ❌ | базовый путь |
| 11 | `spring.jpa.show-sql` | yml | `true` | ❌ | вывод SQL |
| 12 | `spring.jpa.open-in-view` | yml | `false` | ❌ | OSIV |
| 13 | `spring.jpa.hibernate.ddl-auto` | yml | `validate` | ❌ | режим DDL |
| 14 | `spring.liquibase.enabled` | yml | `true` | ❌ | миграции |
| 15 | `spring.liquibase.change-log` | yml | master.yml | ❌ | путь changelog |
| 16 | `spring.servlet.multipart.max-file-size` | yml | `110MB` | ❌ | лимит файла |
| 17 | `spring.servlet.multipart.max-request-size` | yml | `110MB` | ❌ | лимит запроса |
| 18 | `spring.servlet.multipart.file-size-threshold` | yml | `0` | ❌ | порог буферизации |
| 19 | `spring.mail.host` | yml | `smtp.yandex.ru` | ❌ | SMTP-хост |
| 20 | `spring.mail.port` | yml | `465` | ❌ | SMTP-порт |
| 21 | `spring.mail.mail-from` | yml | `${SMTP_USERNAME}@yandex.ru` | ❌ | адрес отправителя |
| 22 | `spring.thymeleaf.prefix/suffix` | yml | `classpath:/templates/`, `.html` | ❌ | шаблоны |
| 23 | `token.signing.key` | yml←env | — | ✅ | ключ JWT |
| 24 | `storage.root` | yml←env | `storage` | ❌ | корень хранилища |
| 25 | `storage.temp-dir` | yml←env | `<root>/tmp` | ❌ | temp-каталог |
| 26 | `storage.max-file-size-bytes` | yml | `104857600` | ❌ | лимит стриминга |
| 27 | `storage.physical-filename.original-name-max-length` | yml | `80` | ❌ | длина префикса имени |
| 28 | `sync.checksum-exists.max-batch-size` | yml | `500` | ❌ | размер batch checksum |
| 29 | `logging.level.liquibase` | yml | `info` | ❌ | уровень логов |
| 30 | `logging.level.ru.tbcarus.photocloudserver` | yml | `INFO` | ❌ | уровень логов приложения |
