# 12. Фоновые процессы

[CONFIRMED] В production src и build.gradle не найдены @Scheduled/@EnableScheduling, @Async/@EnableAsync, Executor/очередь задач, Spring Retry/@Retryable, Kafka/RabbitMQ/JMS, Quartz, retry scheduler или batch worker. Bootstrap содержит только SpringApplication.run. Это отсутствие прикладных jobs в данном репозитории; внешние cron/backup scripts на сервере не исследованы.

| Механизм | Статус | Фактическая альтернатива / источник |
| --- | --- | --- |
| [CONFIRMED] Temp cleanup | Только synchronous best effort | FileItemService catches → deleteIfExists |
| [CONFIRMED] Orphan reconciliation | Не найден | Нет обхода storage/DB сопоставления |
| [CONFIRMED] Physical delete retry | Не найден | IOException после delete DB только log.error |
| [CONFIRMED] Expired refresh cleanup | Не найден | expiry проверяется при использовании JWT; rows остаются |
| [CONFIRMED] Expired email cleanup | Не найден | isExpired вычисляется на запросе; delete метод пуст |
| [CONFIRMED] Metadata processing | Синхронно | Drew extractor внутри upload до commit |
| [CONFIRMED] Thumbnail/video transcode | Не найден | durationSec поле без текущего extractor assignment |
| [CONFIRMED] Email queue/resend worker | Не найден | JavaMailSender.send внутри register/forgotPassword |
| [CONFIRMED] Retry upload/save | Нет повторной постановки | Upload checksum race requery, не очередь |
| [CONFIRMED] Maintenance | Read-only duplicate SQL вручную | db/audit/...before-migration-14.sql не включён в Liquibase |
| [CONFIRMED] DB migrations | Startup Liquibase | Не scheduler и не maintenance timer |

[PARTIAL] В EmailRequestService есть checkAndGenerateCode с ограничением3 за3days, но active controllers вызывают generateEmailRequest напрямую. Resend routes501; это не фоновые процессы.

[RISK] Restart после прерванного streaming/move/delete не запускает восстановление. Долгое SMTP/metadata/copy/download занимает запрос; отдельного SLA/timeout/backpressure code нет. Наличие внутренних JVM/web-server/DB-pool threads не следует интерпретировать как реализованные пользовательские background jobs.

Источники: [bootstrap](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/PhotoCloudServerApplication.java), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [EmailRequestService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java), [EmailService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailService.java), [build.gradle](C:/projects/photo-cloud-server/build.gradle).
