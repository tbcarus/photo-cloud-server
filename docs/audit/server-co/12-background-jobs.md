# Фоновые процессы и maintenance

[CONFIRMED] Поиск по production sources/build.gradle не обнаружил @Scheduled, @EnableScheduling, @Async, @EnableAsync, task executors, messaging brokers/consumers, durable queues или @Retryable. В PhotoCloudServerApplication только SpringBootApplication/run. В service методах работа выполняется непосредственно в запросе.

| Механизм | Найдено | Фактическое поведение / источник |
| --- | --- | --- |
| Scheduled jobs | Нет | Нет scheduled annotations/registration |
| Async upload/processing | Нет | FileItemService upload до завершения записи и DB transaction |
| Async metadata | Нет | DrewFileMetadataExtractor вызывается inline |
| Email queue/outbox | Нет | EmailService.mailSender.send синхронно |
| Temp cleanup job | Нет | Только per-request catch/duplicate cleanup |
| Orphan reconciliation | Нет | БД и disk не сверяются |
| Token/email purge | Нет | Expiry проверяется при обращении; строки сохраняются |
| Retry/backoff | Нет | Нет server retry policy |
| Delete retry | Нет | IOException после удаления DB → log, задача не ставится |
| Lazy folder creation | Да | FolderService в GET root/default upload, не фон |
| Liquibase | Да | Startup migrations из config, не расписание обслуживания |
| Checksum race fallback | Да | Upload constraint catch + reread winner; это не generic retry |
| EmailRequestService.delete | Stub | Пустое тело, фонового caller нет |
| Manual SQL audit migration14 | Да | db/audit SELECT, запуск вручную, не scheduler |

[CONFIRMED] Истечение email code и refresh JWT — вычисление относительно now, не задача, которая обновляет/удаляет rows. EmailRequest.isExpired и JWT parser не выполняют cleanup.

[INFERRED] Старые temp/orphan bytes, expired refresh/email records будут оставаться до внешнего вмешательства. Это потенциальный накопительный эффект; фактические объёмы не измерялись. Внешние cron/systemd/Docker jobs нельзя исключить чтением данного репозитория; их наличие неизвестно.

[CONFIRMED] Server thread pools контейнера/пула БД не равны реализованным business background jobs. Не создавались ни automation, ни scheduler, ни maintenance-скрипты в рамках аудита.

