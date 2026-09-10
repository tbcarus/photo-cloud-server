# Наблюдаемость

## Фактическое логирование

[CONFIRMED] Источники: HttpLoggingFilter.java, EmailService.java, UserService.java, FileItemService.java, DrewFileMetadataExtractor.java, MailConfig.java, application.yml, AbstractIntegrationTest.java.

| Компонент | Уровень / событие | Содержание |
| --- | --- | --- |
| HttpLoggingFilter | INFO каждый прошедший запрос/ответ | Method, URI+query, cached body, response status/body; ANSI colors |
| EmailService.sendEmail | INFO перед send | EmailContext целиком + HTML письма |
| UserService register/forgotPassword | ERROR при MessagingException | e.getCause().getMessage(); может быть null |
| FileItemService cleanup | ERROR | Physical path + IOException stack |
| FileItemService.deleteFile... | ERROR | Physical path после успешного DB deletion |
| DrewFileMetadataExtractor | WARN | Только exception message, без отдельного audit ID |
| Hibernate config | show-sql=true | SQL statements; binding parameters отдельно не настроены |
| Liquibase | INFO | Миграционные сообщения |
| JavaMail | mail.debug=true | SMTP debug, задаётся программно |

[CONFIRMED] Отдельного upload-start/upload-finished/bytes/duration/duplicate-count application logger нет; request logging и exception logs дают лишь часть картины. Metadata failure не переводит upload в failed. Ошибка physical deletion не видна в HTTP статусе 204.

## Чувствительные данные

[RISK] SEC-01 HIGH. HttpLoggingFilter не маскирует JSON. Login/register/password reset body содержит password; login response — accessToken и refreshToken; refresh/logout body — refreshToken. Усечение до 1000 символов не маскировка: пароль/целый токен либо его фрагмент остаются в логах. Authorization header отдельно не логируется этим фильтром, но query code и email логируются в URL.

[RISK] EmailService INFO с EmailContext/HTML включает email-code/link, получателя и имя; EmailRequest находится в context и имеет Lombok @Data/toString. mail.debug включён: фактический объём SMTP diagnostic вывода зависит от библиотеки; не утверждается, что он обязательно печатает plaintext SMTP password.

[RISK] Text/JSON/audio downloads не все попадают в binary-filter list. Для image/video/octet-stream/multipart выводится '[binary]', но другие mime могут декодироваться как UTF-8 и частично попасть в body log, включая содержимое пользовательского документа.

[CONFIRMED] В tracked api-smoke-tests.http найдены JWT-подобные literal значения, email, password/code и локальные файловые пути. Они не воспроизведены в отчёте и не использовались для обращений. Актуальная валидность таких значений неизвестна. Наличие примера в репозитории не доказывает утечку production credentials, но создаёт риск ошибочного повторного использования.

[CONFIRMED] Test harness маскирует accessToken/refreshToken JSON fields и Authorization, но не password/code/email; query также печатается. application-test log level warn подавляет INFO production filter, не System.out тестового harness. Это разница тестовых и production логов.

## Буферизация и полнота логов

[CONFIRMED] HttpLoggingFilter создаёт ContentCachingRequestWrapper и ContentCachingResponseWrapper для **всех** ответов; logResponse вызывает getContentAsByteArray, затем copyBodyToResponse. MAX_BODY_LENGTH применяется только в extractBody после получения bytes и построения строки.

[RISK] OPS-01 HIGH. Ограничение текста лога не ограничивает буфер ответа. Большой download проходит через caching wrapper, несмотря на Resource API и '[binary]' в логах; конкурентные скачивания могут существенно расходовать heap и задерживать доставку. Это вывод по wrapper usage; load profile в аудите не измерялся.

[CONFIRMED] filterChain.doFilter/log/copyBodyToResponse не окружены finally. Если downstream throws, логирование/копирование после него не выполняется этим методом. Нет собственного request duration, correlation ID header/MDC или связи error UUID с конкретным ERROR log. UUID ErrorResponse генерируется локально handler-ом и не записывается глобальным handler в log.

## Metrics / health / tracing / audit trail

| Возможность | As-Is |
| --- | --- |
| Metrics/Prometheus/Micrometer application instrumentation | [CONFIRMED] Явной зависимости/config/измерений не найдено |
| Actuator health | [CONFIRMED] Starter/endpoint не найден |
| Connectivity | [CONFIRMED] GET /api/v1/test public и /test/auth protected; это не проверка DB+storage+SMTP readiness |
| Tracing/OpenTelemetry/span propagation | [CONFIRMED] Собственная интеграция не найдена |
| Durable audit log операций | [CONFIRMED] Таблица/сервис не найдены |
| Login history | [PARTIAL] Только lastLoginAt, не журнал входов/устройств |
| Refresh revoke history | [PARTIAL] revoked + revokedAt одиночного revoke; bulk time отсутствует |
| Upload history | [PARTIAL] uploadedAt в живой записи; hard delete историю удаляет |
| Log sink/retention/rotation/redaction policy | [CONFIRMED] В репозитории не задана |
| Alerting/storage capacity/job failure monitor | [CONFIRMED] Не найден |

[INFERRED] Внешние platform logs/monitoring могут существовать за пределами checkout; отсутствие кода интеграции не доказывает их отсутствие в эксплуатации.

