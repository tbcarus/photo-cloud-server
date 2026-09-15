# Observability

## HTTP logging

SLF4J/Logback и HttpLoggingFilter обеспечивают текстовые логи. Фильтр имеет HIGHEST_PRECEDENCE, оборачивает каждый запрос ContentCachingRequestWrapper и ответ ContentCachingResponseWrapper. После chain пишет INFO request method/URI/query/body и response status/body, затем copyBodyToResponse. Используются ANSI colors.

Текст ограничивается1000 characters при формировании сообщения. Multipart, application/octet-stream, image и video заменяются в тексте на `[binary]`; PDF/audio/ZIP отдельно не исключены. Это не ограничивает response cache: download bytes всё равно полностью буферизуются. JSON passwords/tokens/codes и query не маскируются. Authorization header отдельно этим фильтром не выводится.

Вокруг chain/log/copy нет try/finally; исключение, вышедшее из chain, может пропустить logging в этом проходе. Отдельных полей request ID, user ID, IP, duration и структурированного audit event нет.

## Сервисные логи и ошибки

| Источник | Событие |
| --- | --- |
| EmailService INFO | EmailContext и весь HTML письма, включая ссылку с кодом |
| UserService ERROR | MessagingException отправки; разыменовывается cause |
| FileItemService ERROR | Ошибка cleanup или physical delete с path |
| Metadata extractor WARN | Ошибка извлечения metadata |
| Hibernate show-sql=true | SQL output; bind logging отдельно не задан |
| MailConfig debug=true | SMTP diagnostics; состав реального output не проверен |
| Liquibase INFO | Startup migration messages |

GlobalExceptionHandler не пишет отдельный exception log или stacktrace. ErrorResponse.id генерируется заново; при нормальном прохождении фильтра он может попасть в INFO response body. Следовательно, отсутствие отдельной корреляции не означает, что id никогда не виден в логах. Стабильной связи id с exception/trace, durable error registry и полноты request logging нет.

Security entrypoint/denied handler не ведут отдельный audit journal. lastLoginAt — последняя дата, не история входов; revokedAt заполнен только single revoke. HTTP-логи косвенно отражают бизнес-операции, но не образуют полную историю действий.

## Health, metrics и эксплуатация

Actuator, custom metrics/health/readiness, tracing/exporters и отдельный audit log в приложении отсутствуют. GET test возвращает статический message; GET test/auth дополнительно проходит principal lookup. Они не удостоверяют storage/SMTP/readiness или целостность файлов.

Log file appender, rotation/retention и сборщик в конфигурации проекта не заданы. Сохранение stdout и внешние monitoring/backup средства относятся к неизвестному deployment. Последствия чувствительных логов, буферизации и неполной диагностики сведены в [16](16-risks.md).
