# 14. Наблюдаемость

## HTTP logging

[CONFIRMED] HttpLoggingFilter — @Component @Order(HIGHEST_PRECEDENCE), OncePerRequestFilter. Каждый запрос обёрнут ContentCachingRequestWrapper/ContentCachingResponseWrapper. После chain логируются request method + URI + query + cached body и response status + cached body, затем copyBodyToResponse(). Режим INFO, ANSI colors; duration/request ID/user ID отдельными полями отсутствуют.

[CONFIRMED] extractBody исключает вывод содержимого multipart, application/octet-stream, image, video, заменяя на [binary]. Audio/PDF/ZIP и прочие типы явно не исключены. String bytes декодируются UTF-8 и обрезаются до1000 characters только при формировании log message.

[RISK] Response wrapper кэширует download bytes до копирования в response; getContentAsByteArray также получает полное тело. Порог1000 не ограничивает эту память. Поэтому сервисный streaming upload и бинарная маска лога не доказывают streaming download без memory amplification.

[RISK] Request/response JSON не маскируется: password login/register/reset; refresh в requests; access/refresh в login response и access в refresh response. Query logging включает email и activation/reset code. Authorization header отдельно не логируется этим filter, но это не устраняет body leak.

[CONFIRMED] В filter нет try/finally вокруг chain/log/copy. Если необработанное исключение выходит из chain, logRequest/logResponse/copy не выполнятся этим проходом; полнота request audit не гарантирована.

## Другие источники

| Component | Level / событие | Чувствительные данные / ограничения |
| --- | --- | --- |
| [CONFIRMED] EmailService.sendEmail | INFO email context и полный HTML | Адресат, имя, code и ссылка; EmailContext.context содержит EmailRequest entity |
| [CONFIRMED] UserService register/forgot | ERROR «Nothing was sent» для MessagingException | cause.getMessage может быть null cause→NPE; runtime MailException не ловится |
| [CONFIRMED] FileItemService cleanup/delete | ERROR path и exception | Внутренний root/path/filename видны в логах; cleanup не повторяется |
| [CONFIRMED] DrewFileMetadataExtractor | WARN exception.message | Metadata не извлечена, upload продолжается |
| [CONFIRMED] MailConfig | mail.debug=true | SMTP diagnostic output принудительно включён; точный состав runtime output не изучался |
| [CONFIRMED] spring.jpa.show-sql | true production yaml | SQL statements; bind parameter logging отдельно не настроено |
| [CONFIRMED] Liquibase logger | INFO | Startup migration logs |
| [CONFIRMED] Tests | stdout HTTP helper | Tokens masked, passwords/codes не masked; test profile WARN для app logs |

[RISK] EmailContext — Lombok @Data, context содержит EmailRequest @Data с LAZY user; форматирование context может инициировать lazy access/включать code. Реальный эффект на отправку не воспроизводился. Даже без него отдельный HTML log явно содержит link.

## Errors и корреляция

[CONFIRMED] ErrorResponse.id — случайный UUID каждого ответа. GlobalExceptionHandler не пишет этот id и exception в отдельный log; filter может вывести response body, но независимого correlation/header/trace механизма нет. Сервисный path error и HTTP error трудно связать при параллельной работе без контекста внешних логов.

[CONFIRMED] JSON auth entrypoint и denied handler формируют response без собственных audit logs. Нет отдельного security event journal для login/logout/reset/copy/delete. users.lastLoginAt — только последнее время, не история. revokedAt заполняется single revoke, bulk не заполняет, поэтому это неполный след отзывов.

## Metrics / health / tracing / эксплуатация

[CONFIRMED] Actuator, Micrometer registry, OpenTelemetry/tracing, metrics endpoint, custom health check, log aggregation/rotation/file appender config в проекте не найдены. /api/v1/test — constant response; /test/auth additionally authenticates/loads user, но не проверяет disk capacity, SMTP, целостность объектов или readiness миграций. Swagger не health endpoint.

[INFERRED] JVM/контейнер/прокси могут предоставлять внешнюю observability, но это неизвестно по исходникам. Аудит не утверждает отсутствие внешних backups/log collectors/monitoring в эксплуатации.

Источники: [HttpLoggingFilter](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/HttpLoggingFilter.java), [EmailService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailService.java), [MailConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/MailConfig.java), [GlobalExceptionHandler](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java), [RootController](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RootController.java), [build.gradle](C:/projects/photo-cloud-server/build.gradle).
