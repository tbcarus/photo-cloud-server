# 14. Observability

---

## 1. Итог

**[CONFIRMED]** Наблюдаемость системы ограничена **логированием в stdout**. Метрик, health-endpoint'ов, трассировки и аудита нет.

| Возможность | Есть? |
| --- | --- |
| Логирование | ✅ есть (Logback по умолчанию + собственный HTTP-фильтр) |
| Health endpoints | ❌ нет (Actuator не подключён) |
| Metrics | ❌ нет (Micrometer не подключён) |
| Distributed tracing | ❌ нет |
| Correlation ID / MDC | ❌ нет |
| Audit log | ❌ нет |
| Structured / JSON logging | ❌ нет |
| Логирование в файл, ротация | ❌ нет |
| Alerting | ❌ нет |

---

## 2. Логирование: конфигурация

**[CONFIRMED]**

| Аспект | Значение |
| --- | --- |
| Библиотека | Logback (транзитивно через `spring-boot-starter-web`), API — SLF4J через Lombok `@Slf4j` |
| Конфигурационный файл | **отсутствует** — нет `logback-spring.xml`/`logback.xml` |
| Уровни | `logging.level.liquibase: info`, `logging.level.ru.tbcarus.photocloudserver: INFO` |
| Формат | стандартный Spring Boot (человекочитаемый, не JSON) |
| Назначение вывода | только stdout — `logging.file.name`/`logging.file.path` не заданы |
| Ротация | не настроена |
| В тестах | уровни понижены до `warn` |

**[CONFIRMED][RISK]** Логи существуют только в stdout процесса. Без внешнего сборщика (systemd-journal, docker log driver, агент) они теряются при перезапуске. Конфигурации такого сборщика в репозитории нет.

---

## 3. Классы, использующие логирование

**[CONFIRMED]** `@Slf4j` присутствует в 5 классах:

| Класс | Уровни | Что логирует |
| --- | --- | --- |
| `HttpLoggingFilter` | `INFO` | **каждый** HTTP-запрос и ответ, включая тела |
| `EmailService` | `INFO` | полный `EmailContext` и отрендеренный HTML письма |
| `UserService` | `ERROR` | сбой отправки письма |
| `FileItemService` | `ERROR` | сбой удаления физического файла и сбой cleanup |
| `DrewFileMetadataExtractor` | `WARN` | сбой извлечения метаданных |

**[CONFIRMED]** Полный перечень логирующих вызовов в бизнес-коде — **шесть**:

```java
// UserService.register(), UserService.forgotPassword()
log.error("Nothing was sent {}", e.getCause().getMessage());

// FileItemService.deleteFileForCurrentUser()
log.error("Не удалось удалить физический файл {} после удаления записи из БД", path, ex);

// FileItemService.deleteIfExists()
log.error("Не удалось удалить файл при cleanup: {}", path, cleanupEx);

// DrewFileMetadataExtractor.extract()
log.warn("Не удалось извлечь metadata файла: {}", ex.getMessage());

// EmailService.sendEmail()
log.info("Sending email: {} with html body: {}", email, html);
```

**[CONFIRMED][RISK]** Бизнес-события **не логируются вообще**: нет записей о входе, выходе, регистрации, загрузке файла, удалении, отзыве токена, отказе в доступе. Единственный источник информации об этих событиях — сырой HTTP-лог фильтра.

---

## 4. HttpLoggingFilter

**[CONFIRMED]** `@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`, `OncePerRequestFilter`.

Формат:

```
>>> POST /api/v1/auth/login | body: {"email":"...","password":"..."}      (зелёный)
<<< 200 | body: {"accessToken":"eyJ...","refreshToken":"eyJ..."}          (синий/красный при >=400)
```

| Особенность | Детали |
| --- | --- |
| Обрезка тела | 1000 символов + `...[truncated]` |
| Пропуск бинарных данных | `multipart/`, `application/octet-stream`, `image/`, `video/` → `[binary]` |
| Цвета | ANSI-escape (`\033[1;32m` и т.д.) — **[RISK]** мусор в файловых логах и в системах сбора |
| Порядок | логирует **после** `filterChain.doFilter()`, то есть запрос и ответ выводятся вместе, после обработки |
| Буферизация | `ContentCachingRequestWrapper`/`ContentCachingResponseWrapper` — **[RISK]** тело ответа буферизуется в памяти целиком, включая скачиваемые файлы |

**[CONFIRMED][RISK]** Для `GET /files/{id}/download` `Content-Type` — это `detectedMimeType` файла. Для `image/*` и `video/*` тело помечается `[binary]`, но для `application/pdf`, `text/plain`, `application/zip` **тело будет прочитано и залогировано** (обрезанное до 1000 символов) — то есть содержимое пользовательских документов попадает в лог.

**[CONFIRMED][RISK]** `ContentCachingResponseWrapper` копирует тело ответа в память **независимо от размера** — для скачивания 100 MiB видео это 100 MiB heap на каждый параллельный запрос. Это одновременно проблема наблюдаемости и производительности.

---

## 5. Логирование чувствительных данных

**[CONFIRMED][RISK] HIGH.** Сводная таблица утечек в лог при уровне `INFO` (значение по умолчанию в `application.yml`):

| Endpoint | Что попадает в лог |
| --- | --- |
| `POST /auth/login` | **пароль в открытом виде**; **access и refresh token** в ответе |
| `POST /auth/register` | **пароль в открытом виде** |
| `POST /auth/password/reset/confirm` | **новый пароль в открытом виде** и код сброса |
| `POST /auth/refresh-token` | refresh token (запрос) и access token (ответ) |
| `POST /auth/logout`, `/logout-others` | refresh token |
| `GET /auth/register/confirm?code=` | код активации в URL |
| `POST /auth/password/reset/request?email=` | email в URL |
| `GET /files/{id}`, `GET /files` | метаданные, включая **GPS-координаты** (`latitude`, `longitude`) |
| `GET /files/checksums` | все checksum и имена файлов пользователя |
| `EmailService.sendEmail()` | адрес получателя и **полная ссылка с кодом активации/сброса** |
| Hibernate (`show-sql: true`) | SQL-запросы; параметры — при повышении уровня Hibernate-логгеров |

**[CONFIRMED]** Маскирования в production-коде **нет**. При этом в тестовой инфраструктуре оно реализовано (`AbstractIntegrationTest.maskSensitiveJson()`, `maskAuthorization()`) — то есть проблема осознана в тестах, но не перенесена в `HttpLoggingFilter`.

**[CONFIRMED]** Заголовок `Authorization` фильтром не логируется (логируются только метод, URL и тела) — единственное смягчающее обстоятельство.

---

## 6. Security-related logging

**[CONFIRMED]** Специализированного security-лога **нет**. Не логируются:

| Событие | Логируется? |
| --- | --- |
| Успешный вход | только как HTTP-строка фильтра |
| Неудачный вход | только как `401` в HTTP-строке |
| Серия неудачных попыток | нет агрегации, нет обнаружения brute force |
| Отзыв токена | нет |
| Попытка доступа к чужому объекту | нет (виден только `404`) |
| Использование истёкшего/поддельного JWT | нет — `JwtAuthenticationFilter` перехватывает исключение и молча отвечает `401` |
| Смена пароля | нет |
| Активация аккаунта | нет |
| Загрузка/удаление файла | нет |
| IP-адрес клиента | **нигде не логируется** |
| User-Agent | не логируется |

**[CONFIRMED][RISK]** `JwtAuthenticationFilter` в обоих catch-блоках вызывает `SecurityContextHolder.clearContext()` и `entryPoint.commence()` **без единой строки лога** — атаки на подбор/подделку токенов невидимы.

---

## 7. Upload logging

**[CONFIRMED]** Специального логирования загрузки нет. В логе будет:

```
>>> POST /api/v1/files | body: [binary]
<<< 200 | body: {"id":42,"folderId":7,"originalFilename":"photo.jpg",...}
```

Не логируются: размер файла, checksum, определённый MIME, целевая папка, время обработки, был ли это дубль. Отличить новую загрузку от идемпотентного возврата существующей записи по логам **невозможно**.

---

## 8. Error logging

**[CONFIRMED][RISK]** Наиболее серьёзный пробел: **`GlobalExceptionHandler` не логирует ни одно исключение.** Все 23 обработчика формируют `ErrorResponse` и возвращают его, не записывая ничего.

Следствия:

1. `ErrorResponse.id` (`UUID.randomUUID()`) генерируется для корреляции, но **нигде не сохраняется** — пользователь может сообщить id, а найти по нему в логах нечего.
2. `500 DATABASE_CONSTRAINT_VIOLATION` возвращается клиенту **без записи причины и stacktrace**.
3. Необработанные исключения (`NullPointerException`, `NoSuchElementException`, `IOException`) логируются стандартным Spring Boot `DispatcherServlet`/Tomcat, то есть stacktrace всё-таки появится — но для *обработанных* исключений диагностики нет.
4. В логе будет видна только строка `<<< 500 | body: {...}` от `HttpLoggingFilter`.

---

## 9. Audit logging

**[CONFIRMED]** Отсутствует полностью: нет таблицы аудита, нет `@EntityListeners`/Hibernate Envers, нет отдельного логгера.

Восстановить историю «кто, что и когда сделал с файлом» невозможно. Косвенные следы:

| След | Что даёт |
| --- | --- |
| `users.created_at`, `last_update`, `last_login_at` | момент регистрации, последнего изменения, последнего входа |
| `folder.created_at`, `updated_at` | создание/изменение папки |
| `file_item.uploaded_at` | момент загрузки |
| `stored_object.created_at` | момент появления физического объекта |
| `refresh_token.expires`, `revoked_at` | косвенно — время login (`expires` − 7 дней) и logout |
| `email_requests.created_at` | момент запроса кода |

**[CONFIRMED]** Удалённые сущности не оставляют следа вообще — hard delete.

---

## 10. Metrics

**[CONFIRMED]** Отсутствуют. В `build.gradle` нет `spring-boot-starter-actuator`, нет `micrometer-*`.

Не собираются:

| Метрика | Почему важна для этой системы |
| --- | --- |
| Время ответа по endpoint'ам | upload — потенциально долгая операция |
| Количество и статусы запросов | базовый мониторинг |
| Использование пула соединений (Hikari) | транзакции переплетены с файловыми операциями |
| Размер занятого хранилища | квот нет, диск может закончиться |
| Количество файлов/пользователей | ёмкостное планирование |
| Количество orphan-файлов | расхождение ФС ↔ БД никак не отслеживается |
| JVM heap / GC | `ContentCachingResponseWrapper` буферизует тела ответов |
| Ошибки отправки писем | ошибки поглощаются |
| Активные refresh-токены | таблица растёт монотонно |

---

## 11. Health endpoints

**[CONFIRMED]** Отсутствуют. Нет `/actuator/health`, `/actuator/info`, `/actuator/metrics`.

Ближайший аналог — `GET /api/v1/test`, возвращающий статический `{"message":"All good! Permit all connection"}`. **[CONFIRMED]** Он проверяет только то, что процесс отвечает: не проверяются доступность БД, доступность каталога хранилища, свободное место, доступность SMTP.

**[CONFIRMED][RISK]** Для оркестраторов (Docker healthcheck, k8s liveness/readiness) пригодного endpoint'а нет: `/api/v1/test` вернёт `200` даже при упавшей БД, потому что не обращается к ней.

---

## 12. Tracing

**[CONFIRMED]** Отсутствует:

- нет Micrometer Tracing / Sleuth / OpenTelemetry;
- нет заголовков `traceparent`/`X-Request-Id`;
- нет MDC-контекста (в логах нет ни correlation id, ни имени пользователя);
- при параллельных запросах строки `>>>` и `<<<` от разных запросов **перемешиваются** в логе, и сопоставить их можно только по имени потока в стандартном префиксе Logback.

---

## 13. Что можно узнать из логов в проблемной ситуации

**[INFERRED]** Практическая оценка диагностируемости:

| Вопрос | Ответим по логам? |
| --- | --- |
| Сколько запросов пришло и с какими статусами? | ✅ да (`HttpLoggingFilter`) |
| Какой пользователь выполнил операцию? | ⚠️ только для login (email в теле); для остальных запросов — нет |
| С какого IP пришёл запрос? | ❌ нет |
| Почему вернулся `500`? | ⚠️ только для необработанных исключений (Spring залогирует stacktrace); для обработанных — нет |
| Почему не пришло письмо? | ⚠️ `log.error` есть, но может упасть с NPE |
| Почему остался orphan-файл? | ⚠️ `log.error` есть только для сбоя удаления; для аварийного завершения — нет |
| Сколько места занято? | ❌ нет |
| Кто и когда удалил файл? | ❌ нет |
| Идёт ли атака подбором пароля? | ❌ нет (только косвенно — серия `401`) |
| Насколько медленно работает upload? | ❌ нет (время обработки не логируется) |

---

## 14. Сводка проблем наблюдаемости

| # | Проблема | Severity |
| --- | --- | --- |
| 1 | Логирование паролей, токенов и кодов в открытом виде | **HIGH** |
| 2 | `GlobalExceptionHandler` не логирует исключения; `ErrorResponse.id` бесполезен | **HIGH** |
| 3 | Нет health-endpoint'а, проверяющего БД и хранилище | MEDIUM |
| 4 | Нет метрик | MEDIUM |
| 5 | `ContentCachingResponseWrapper` буферизует тела ответов целиком (в т.ч. скачиваемые файлы) | MEDIUM |
| 6 | Нет correlation id / MDC | MEDIUM |
| 7 | Нет security-логирования (вход, отказ, подделка токена, IP) | MEDIUM |
| 8 | Нет аудита действий с файлами | MEDIUM |
| 9 | Логи только в stdout, без ротации и структурирования | MEDIUM |
| 10 | ANSI-цвета в логах | LOW |
| 11 | `show-sql: true` и `mail.debug: true` в production-конфигурации | LOW |
| 12 | Содержимое не-медиа файлов (PDF, txt) попадает в лог при скачивании | MEDIUM |
