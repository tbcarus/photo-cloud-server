# Background processing

Прикладных scheduled jobs, async workers, очередей, scheduler, Spring Retry и фоновой обработки metadata в сервере нет. Внутренние потоки JVM, web server и connection pool не являются отдельными пользовательскими workflow.

| Работа | Текущий механизм |
| --- | --- |
| SHA-256, MIME, image metadata | Синхронно внутри upload |
| Copy и physical delete | Синхронно внутри HTTP operation |
| Email | Thymeleaf и SMTP в register/reset request |
| Temp/final cleanup | Best-effort catch в FileItemService |
| Duplicate upload recovery | Reread после DataIntegrityViolation; без job и повторного INSERT |
| DB migration | Liquibase при startup |
| Pre-migration duplicate diagnostic | Отдельный read-only SQL; автоматически master его не запускает |

Не выполняются: очистка истёкших refresh/email rows, temp после crash, orphan reconciliation, retry удаления bytes, checksum scrub, trash retention, thumbnails/transcode, email resend worker. EmailRequestService.delete — STUB с пустым телом. Лимитер email code — UNUSED и не является maintenance task.

Restart не восстанавливает незавершённые upload/copy/delete. Наличие внешнего cron или backup агента в эксплуатации не установлено; отсутствие jobs относится к приложению.
