# Журнал точечной верификации

Здесь только22 вопроса, для которых читался production-код, SQL/config или конкретный test. Один вопрос может потребовать нескольких файлов; один файл мог отвечать на несколько вопросов. Это число вопросов, не число открытий файла или просмотренных классов. Причины A_ONLY/B_ONLY обозначают существенную single-source находку; CRITICAL_DETAIL — уточнение важной согласованной семантики. AMBIGUOUS включает подозрение на чрезмерно категоричный вывод.

Проверки статические, без server/test/DB/SMTP запуска, full repository scan, новой карты приложения или изменения production. Источники A/B идентифицированы в [19](19-source-traceability.md). Приведённые номера строк относятся к рабочему дереву при консолидации; ссылки открывают сам файл.

## VER-SRV-001 — Identity и scope duplicate

**Reason:** CRITICAL_DETAIL

**Audit A:** A03 §7–8; A04 §8.1–8.3; A07 §5: user/folder/checksum, разные physical copies.

**Audit B:** B03 FileItem/StoredObject; B07: та же модель, уточнено отсутствие bytes check.

**Question:** Каковы ключ duplicate, источники checksum, порядок duplicate/name check и transaction boundary?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:74](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L74)
- [src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java:19](../../../src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java#L19)
- [src/main/resources/db/changelog/table/13-file-api-operations.sql:4](../../../src/main/resources/db/changelog/table/13-file-api-operations.sql#L4)
- [src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql:4](../../../src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql#L4)

**Verified result:** Unique установлен на FileItem(user,folder,checksum), StoredObject checksum не unique. Upload первым проверяет duplicate и возвращает старый DTO без проверки bytes; copy пишет checksum в обе сущности. IO предшествует DB transaction.

**Applied to:** [03-data-model.md](03-data-model.md) identity;[04-database.md](04-database.md) constraints;[05-api.md](05-api.md) files;[07-file-storage.md](07-file-storage.md) upload;[08-business-processes.md](08-business-processes.md) upload;[09-state-model.md](09-state-model.md) state;[15-feature-matrix.md](15-feature-matrix.md) capabilities.

## VER-SRV-002 — Move и checksum conflict

**Reason:** CRITICAL_DETAIL

**Audit A:** A05 §8.6; A16 C1: move checksum500.

**Audit B:** B05 move; B16 C1: name409 может возникнуть раньше.

**Question:** Когда move даёт409, а когда500?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:195](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L195)
- [src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java:142](../../../src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java#L142)
- [src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql:18](../../../src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql#L18)

**Verified result:** Rename/move меняют только логические поля. Move вызывает name check, не checksum check; при прошедшем name check нарушение unique переводится в500 DATABASE_CONSTRAINT_VIOLATION. Copy имеет отдельный checksum409.

**Applied to:** [05-api.md](05-api.md) conflict matrix;[07-file-storage.md](07-file-storage.md) move;[10-errors-and-recovery.md](10-errors-and-recovery.md) mapping;[16-risks.md](16-risks.md) RISK-SRV-013/014.

## VER-SRV-003 — Owner/delete и последняя ссылка

**Reason:** CRITICAL_DETAIL

**Audit A:** A04 §8.6; A07 delete: owner удаляет все; краткая API-таблица опускает non-owner.

**Audit B:** B07/B08: обе ветки, отдельный physical cleanup.

**Question:** Зависит ли physical lifecycle от последней логической ссылки?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:258](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L258)
- [src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java:34](../../../src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java#L34)

**Verified result:** После user-scoped lookup сравниваются owners. Owner удаляет все references, flush и StoredObject в transaction, затем bytes. Non-owner удаляет только свою запись, не проверяя число оставшихся references. Refcount/last-reference GC здесь нет. IO delete failure только log и204.

**Applied to:** [03-data-model.md](03-data-model.md) lifecycle;[04-database.md](04-database.md) deletes;[05-api.md](05-api.md) DELETE;[07-file-storage.md](07-file-storage.md);[08-business-processes.md](08-business-processes.md) delete;[09-state-model.md](09-state-model.md) state;[16-risks.md](16-risks.md) RISK-SRV-011/012.

## VER-SRV-004 — CONF-SRV-001 — гарантии очистки при IO

**Reason:** CONFLICT

**Audit A:** A07 §8, строки4/11: при failed move final не создан, failed copy copied удаляется, состояние консистентно.

**Audit B:** B07 матрица отказов; B16 T4: flags ещё false, partial target может остаться.

**Question:** Какие paths реально очищает catch до успешного возврата IO?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:117](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L117)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:231](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L231)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:337](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L337)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:480](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L480)

**Verified result:** movedToFinal/copied устанавливаются после успешного IO return. До этого catch не гарантирует очистку final/partial copy; temp cleanup и последующий final cleanup best effort. Утверждение A о безусловной очистке сужено. Сценарий реальной аварии не воспроизводился. Также ATOMIC_MOVE с fallback и отсутствие retry collision не дают доказательства универсального overwrite/collision outcome.

**Applied to:** [07-file-storage.md](07-file-storage.md) compensation;[10-errors-and-recovery.md](10-errors-and-recovery.md) recovery;[16-risks.md](16-risks.md) RISK-SRV-007/009/022.

## VER-SRV-005 — Mapping после commit

**Reason:** B_ONLY

**Audit A:** A07 обсуждает ошибки БД, но не самостоятельную ошибку mapping после commit.

**Audit B:** B02/B07; B16 D8: mapper внутри cleanup try.

**Question:** Может ли cleanup удалить bytes уже committed records?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:122](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L122)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:239](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L239)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:360](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L360)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:410](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L410)

**Verified result:** TransactionTemplate.execute завершает DB transaction до return; fileItemMapper.toDto вызывается затем внутри RuntimeException cleanup. При его ошибке bytes могут удалиться при сохранённых rows. Это условный риск control flow, не воспроизведённый mapper failure. Severity HIGH из-за возможной потери единственной binary copy (B MEDIUM).

**Applied to:** [04-database.md](04-database.md) transactions;[07-file-storage.md](07-file-storage.md) failures;[10-errors-and-recovery.md](10-errors-and-recovery.md) recovery;[16-risks.md](16-risks.md) RISK-SRV-008.

## VER-SRV-006 — CONF-SRV-002 — race-safe folders

**Reason:** CONFLICT

**Audit A:** A00 §9; A10 §4.5; A15 folders: создание ROOT/CAMERA/FILES race-safe с успешным reread.

**Audit B:** B08 folder operations; B16 D5: same-transaction catch ненадёжен.

**Question:** Есть ли отдельная успешная transaction для recovery, и какие folders создаёт root GET?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java:31](../../../src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java#L31)
- [src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java:149](../../../src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java#L149)
- [src/main/resources/db/changelog/table/12-folder-api-invariants.sql:4](../../../src/main/resources/db/changelog/table/12-folder-api-invariants.sql#L4)

**Verified result:** ROOT GET создаёт только ROOT; default upload — нужную CAMERA/FILES. SaveAndFlush и catch+reread находятся в одной @Transactional операции, без новой transaction/savepoint recovery. DB unique обеспечивает единственность, но успешный ответ проигравшему запросу не гарантирован. Обещание A исключено; runtime concurrency не запускалась. Severity MEDIUM: риск конкурентного отказа, не подтверждённый инцидент.

**Applied to:** [05-api.md](05-api.md) folders;[07-file-storage.md](07-file-storage.md) default;[08-business-processes.md](08-business-processes.md) bootstrap;[09-state-model.md](09-state-model.md) folder;[16-risks.md](16-risks.md) RISK-SRV-016;[17-open-questions.md](17-open-questions.md) OPEN-SRV-004.

## VER-SRV-007 — Concurrent cycle protection

**Reason:** B_ONLY

**Audit A:** A03/A04: self/descendant service check, без самостоятельного сценария встречных moves.

**Audit B:** B16 D6: concurrent opposing moves могут образовать цикл.

**Question:** Каковы locks/version/SQL границы ацикличности?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java:95](../../../src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java#L95)
- [src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java:192](../../../src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java#L192)
- [src/main/java/ru/tbcarus/photocloudserver/repository/FolderRepository.java:13](../../../src/main/java/ru/tbcarus/photocloudserver/repository/FolderRepository.java#L13)
- [src/main/java/ru/tbcarus/photocloudserver/model/Folder.java:27](../../../src/main/java/ru/tbcarus/photocloudserver/model/Folder.java#L27)
- [src/main/resources/db/changelog/table/12-folder-api-invariants.sql:12](../../../src/main/resources/db/changelog/table/12-folder-api-invariants.sql#L12)

**Verified result:** Проверка читает parent chain и затем сохраняет parent; явных locks/@Version нет в проверенных entity/repository paths. SQL CHECK только self-parent. Последовательный запрет потомка не является гарантией против взаимных concurrent moves.

**Applied to:** [04-database.md](04-database.md) invariants;[08-business-processes.md](08-business-processes.md) folders;[09-state-model.md](09-state-model.md) categories;[16-risks.md](16-risks.md) RISK-SRV-017.

## VER-SRV-008 — DDL ownership/cascades и destructive migration

**Reason:** CRITICAL_DETAIL

**Audit A:** A04 §8.4–8.7: SQL каскады, файловая очистка отсутствует.

**Audit B:** B04; B16 D1/D7: cross-owner NO ACTION может блокировать cascade; DROP10 без переноса.

**Question:** Совпадают ли SQL и API delete, обеспечено ли owner equality?

**Code inspected:**

- [src/main/resources/db/changelog/table/10-replace-media-file-with-file-model.sql:4](../../../src/main/resources/db/changelog/table/10-replace-media-file-with-file-model.sql#L4)
- [src/main/resources/db/changelog/table/12-folder-api-invariants.sql:4](../../../src/main/resources/db/changelog/table/12-folder-api-invariants.sql#L4)
- [src/main/resources/db/changelog/table/13-file-api-operations.sql:4](../../../src/main/resources/db/changelog/table/13-file-api-operations.sql#L4)
- [src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql:4](../../../src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql#L4)

**Verified result:** Migration10 действительно DROP media_file CASCADE без переноса старых rows. User и Folder.parent cascades существуют, Item→Folder/Object NO ACTION; FK одиночные, owner equality/длинная ацикличность не обеспечены. SQL-delete не вызывает filesystem code; cross-owner references могут препятствовать каскаду. Конечный checksum constraint подтверждён13/14; остальная согласованная схема взята из аудитов, без reread всех migrations.

**Applied to:** [03-data-model.md](03-data-model.md) relationships;[04-database.md](04-database.md) schema;[07-file-storage.md](07-file-storage.md) lifecycle;[16-risks.md](16-risks.md) RISK-SRV-012/018/019.

## VER-SRV-009 — CONF-SRV-003 — error ID и логи

**Reason:** CONFLICT

**Audit A:** A10 §3; A16 H3: ErrorResponse.id нигде не логируется, найти его невозможно.

**Audit B:** B14 Errors: отдельного exception log нет, но id может выводиться в response body filter.

**Question:** Попадает ли JSON error body в INFO и что означает limit1000?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/config/filter/HttpLoggingFilter.java:31](../../../src/main/java/ru/tbcarus/photocloudserver/config/filter/HttpLoggingFilter.java#L31)
- [src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java:23](../../../src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java#L23)

**Verified result:** Фильтр после chain пишет cached JSON response включая id; отдельного exception/trace registry у advice нет. Limit1000 применяется к text, не cache; весь download response буферизуется. При escaping exception нет finally logging. A absolute never-logged неверно; не обещается logging при каждом отказе. Download memory severity HIGH (A MEDIUM/B HIGH), diagnostics MEDIUM (A HIGH), поскольку body-id всё же может быть доступен.

**Applied to:** [10-errors-and-recovery.md](10-errors-and-recovery.md) error DTO;[13-observability.md](13-observability.md) logging;[16-risks.md](16-risks.md) RISK-SRV-001/029/030.

## VER-SRV-010 — CONF-SRV-004 — sender configuration

**Reason:** CONFLICT

**Audit A:** A11 §2/8: mail-from=${SMTP_USERNAME}@yandex.ru.

**Audit B:** B11 runtime table: literal sender address.

**Question:** Отправитель literal или выражение?

**Code inspected:**

- [src/main/resources/application.yml:25](../../../src/main/resources/application.yml#L25)

**Verified result:** Задано ${SMTP_USERNAME}@yandex.ru. Верен A, B исправлен. Секретные environment values не читались; в документах только выражение.

**Applied to:** [11-configuration.md](11-configuration.md) runtime/mail.

## VER-SRV-011 — Unhandled errors и multipart binding

**Reason:** AMBIGUOUS

**Audit A:** A10: fixed Spring-default500 body; missing file якобы MissingServletRequestParameterException→custom400.

**Audit B:** B05/B10: missing part/IO/framework не покрыты единым advice; /error dispatch неопределён.

**Question:** Какие custom handlers существуют и можно ли гарантировать body остальных ошибок?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java:54](../../../src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java#L54)
- [src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java:20](../../../src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java#L20)
- [src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java:35](../../../src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java#L35)

**Verified result:** Upload принимает @RequestParam MultipartFile. Advice содержит ordinary missing-query handler, но не missing multipart part/catch-all/IO. /error не permitAll. Из кода приложения нельзя гарантировать единый Spring-default JSON или все secondary statuses. Основная спецификация содержит только достоверные custom mappings; exact framework outcomes OPEN-SRV-030. Недостаточность доказательства не сосчитана как новый CONFLICT.

**Applied to:** [05-api.md](05-api.md) common/framework;[10-errors-and-recovery.md](10-errors-and-recovery.md) boundaries;[17-open-questions.md](17-open-questions.md) OPEN-SRV-030.

## VER-SRV-012 — Range, headers и CORS: проверка подозрительных категоричных выводов

**Reason:** AMBIGUOUS

**Audit A:** A07 §12/A16 E6: отсутствие ResourceHttpRequestHandler доказывает отсутствие Range; A06 CORS: browser невозможен.

**Audit B:** B05 framework/B06 transport: собственный контракт не задан; внешний/библиотечный неизвестен.

**Question:** Достаточно ли controller/security code для таких абсолютных утверждений?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java:105](../../../src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java#L105)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:185](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L185)
- [src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java:31](../../../src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java#L31)

**Verified result:** Controller возвращает ResponseEntity&lt;Resource&gt; с200/MIME/attachment, Resource — UrlResource. Нет собственной Range/conditional logic, что не доказывает отсутствие framework support. Конкретный wire contract оставлен OPEN-SRV-029; поиск локального cached spring-webmvc не дал доступного источника, dependency/runtime исследование не расширялось. Security не задаёт CORS; вывод о невозможности любого browser client исключён как необоснованный.

**Applied to:** [05-api.md](05-api.md) framework;[06-auth-security.md](06-auth-security.md) transport;[07-file-storage.md](07-file-storage.md) download;[17-open-questions.md](17-open-questions.md) OPEN-SRV-029.

## VER-SRV-013 — Auth validation/revocation order

**Reason:** CRITICAL_DETAIL

**Audit A:** A06/A09: TTL/logout/ban и Optional.get.

**Audit B:** B06 уточняет roles из DB, exp источник, revoked-first, public invalid Bearer.

**Question:** Каков точный порядок и прекращение доступа после logout/reset/ban?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java:37](../../../src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java#L37)
- [src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java:81](../../../src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java#L81)
- [src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java:121](../../../src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java#L121)
- [src/main/java/ru/tbcarus/photocloudserver/service/UserService.java:72](../../../src/main/java/ru/tbcarus/photocloudserver/service/UserService.java#L72)
- [src/main/java/ru/tbcarus/photocloudserver/config/filter/JwtAuthenticationFilter.java:34](../../../src/main/java/ru/tbcarus/photocloudserver/config/filter/JwtAuthenticationFilter.java#L34)
- [src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java:35](../../../src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java#L35)

**Verified result:** Access20min/refresh7days; JWT claims/type/signature и exp, DB token exact lookup. Refresh сначала user, затем revoked, затем JWT; expires DB не валидирует. Roles из UserDetails; enabled/banned только login. Logout ownership без expiry; single revokedAt, bulk без него. Нет общей lock/transaction refresh+revoke. Missing access user Optional.get вне JWT catch, missing refresh user→INVALID_REFRESH_TOKEN. Public URL filter не исключает.

**Applied to:** [05-api.md](05-api.md) auth;[06-auth-security.md](06-auth-security.md);[08-business-processes.md](08-business-processes.md) auth;[09-state-model.md](09-state-model.md) tokens;[16-risks.md](16-risks.md) RISK-SRV-002–004/025.

## VER-SRV-014 — Email owner/concurrency/origin; критические границы workflow

**Reason:** B_ONLY

**Audit A:** A06/A08: code3days, reset invalidation/no revoke, отсутствие общей register transaction; host link упомянут.

**Audit B:** B06/B16 S8/S11/D11/D12: unused confirmEmail ownership, concurrent used, origin trust.

**Question:** Какие code/user изменения и side effects входят в реальные flows?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java:30](../../../src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java#L30)
- [src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java:50](../../../src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java#L50)
- [src/main/java/ru/tbcarus/photocloudserver/service/UserService.java:37](../../../src/main/java/ru/tbcarus/photocloudserver/service/UserService.java#L37)
- [src/main/java/ru/tbcarus/photocloudserver/service/EmailService.java:42](../../../src/main/java/ru/tbcarus/photocloudserver/service/EmailService.java#L42)
- [src/main/java/ru/tbcarus/photocloudserver/model/EmailRequest.java:27](../../../src/main/java/ru/tbcarus/photocloudserver/model/EmailRequest.java#L27)
- [src/main/java/ru/tbcarus/photocloudserver/repository/EmailRequestRepository.java:13](../../../src/main/java/ru/tbcarus/photocloudserver/repository/EmailRequestRepository.java#L13)

**Verified result:** Active confirmRegistration связывает user с code; unused confirmEmail выбирает отдельный email без owner equality. Confirm/reset transactional, used read-check-write без lock/version; reset погашает окно3days, JWT не отзывает. Register/login saves отдельные, mail синхронный; catch MessagingException cause может NPE. Link использует request scheme/serverName/port/context. Неиспользуемый helper не описывается как доступная HTTP-уязвимость.

**Applied to:** [04-database.md](04-database.md) transactions;[06-auth-security.md](06-auth-security.md) email;[08-business-processes.md](08-business-processes.md) flows;[10-errors-and-recovery.md](10-errors-and-recovery.md) email;[16-risks.md](16-risks.md) RISK-SRV-026/028;[19-source-traceability.md](19-source-traceability.md) exclusions.

## VER-SRV-015 — Long extension и санитарная обработка

**Reason:** B_ONLY

**Audit A:** A07: logical255/physical255; A16 E7 mentions names.

**Audit B:** B07; B16 D9: long extension может превысить logical255.

**Question:** Ограничивается ли всё имя при длинном extension?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/storage/FilenameSanitizer.java:11](../../../src/main/java/ru/tbcarus/photocloudserver/service/storage/FilenameSanitizer.java#L11)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:96](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L96)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:315](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L315)

**Verified result:** limitOriginalNameWithExtension сохраняет extensionWithDot целиком и минимум1 base char, поэтому logical result может быть >255. Physical component дополнительно обрезается отдельно. Dangerous/control symbols, включая quote, заменяются: безусловная проблема quote injection для нормального API-имени не подтверждена. Unicode filename* вопрос сохранён отдельно.

**Applied to:** [05-api.md](05-api.md) DTO;[07-file-storage.md](07-file-storage.md) names;[16-risks.md](16-risks.md) RISK-SRV-020/034.

## VER-SRV-016 — Copy physical metadata

**Reason:** B_ONLY

**Audit A:** A07/A08: independent copy, унаследованные даты/metadata.

**Audit B:** B07; B16 D10/T7: новая filename extension и старая fileExtension, checksum не пересчитывается.

**Question:** Какие physical поля вычисляются заново?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:213](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L213)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:403](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L403)

**Verified result:** Filename/path генерируются заново, bytes копируются; StoredObject extension/checksum/size/MIME/type наследуются. capturedAt/metadata копируются, uploadedAt новый. Несовпадение нового имени и extension column возможно; source corruption повторным hash не проверяется.

**Applied to:** [03-data-model.md](03-data-model.md) lifecycle;[05-api.md](05-api.md) copy;[07-file-storage.md](07-file-storage.md) copy;[16-risks.md](16-risks.md) RISK-SRV-021.

## VER-SRV-017 — Lexical path protection

**Reason:** B_ONLY

**Audit A:** A07: normalize/startsWith как path traversal protection.

**Audit B:** B07/B16 T6: realpath/symlink не покрыты.

**Question:** Каков точный предел resolver?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/storage/StoragePathResolver.java:15](../../../src/main/java/ru/tbcarus/photocloudserver/service/storage/StoragePathResolver.java#L15)

**Verified result:** Проверяется absolute normalized target.startsWith(root), не canonical realpath. Защита ограничена лексическими путями; локальные symlink/reparse подмены не исключены этим методом.

**Applied to:** [06-auth-security.md](06-auth-security.md) file boundaries;[07-file-storage.md](07-file-storage.md) resolver;[16-risks.md](16-risks.md) RISK-SRV-023.

## VER-SRV-018 — Checksum exists contract

**Reason:** CRITICAL_DETAIL

**Audit A:** A05 §8.10; A08: normalized folder-scoped pre-check.

**Audit B:** B05/B08: raw batch до dedup, без trim, order partitions.

**Question:** Каков validation/order/источник существования?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncService.java:26](../../../src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncService.java#L26)
- [src/main/java/ru/tbcarus/photocloudserver/model/dto/ChecksumExistsRequest.java:11](../../../src/main/java/ru/tbcarus/photocloudserver/model/dto/ChecksumExistsRequest.java#L11)
- [src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java:45](../../../src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java#L45)

**Verified result:** Raw length проверяется до folder lookup и dedup; DTO64hex без trim; lowercase Locale.ROOT и LinkedHashSet. Query lower(FileItem.checksum) по user/folder. existing/missing сохраняют порядок первого появления; no IDs, writes, reservation или FS check.

**Applied to:** [05-api.md](05-api.md) checksums;[07-file-storage.md](07-file-storage.md) existence;[08-business-processes.md](08-business-processes.md) pre-check;[15-feature-matrix.md](15-feature-matrix.md) sync.

## VER-SRV-019 — Media DTO и time semantics

**Reason:** CRITICAL_DETAIL

**Audit A:** A03/A05/A07: FileItem physical fields из StoredObject, image metadata, fallback capture.

**Audit B:** B03/B07/B16 C5: local datetime/system timezone, nullable details.

**Question:** Какие wire names/источники и что реально извлекается?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/model/dto/FileItemDto.java:15](../../../src/main/java/ru/tbcarus/photocloudserver/model/dto/FileItemDto.java#L15)
- [src/main/java/ru/tbcarus/photocloudserver/model/dto/FileMetadataDto.java:16](../../../src/main/java/ru/tbcarus/photocloudserver/model/dto/FileMetadataDto.java#L16)
- [src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/FileItemMapper.java:11](../../../src/main/java/ru/tbcarus/photocloudserver/model/dto/mapper/FileItemMapper.java#L11)
- [src/main/java/ru/tbcarus/photocloudserver/service/metadata/DrewFileMetadataExtractor.java:29](../../../src/main/java/ru/tbcarus/photocloudserver/service/metadata/DrewFileMetadataExtractor.java#L29)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:90](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L90)

**Verified result:** FileItemMapper объединяет FileItem и StoredObject; fNumber задан JsonProperty. Extractor только image, dimensions JPEG/PNG, EXIF Original затем Digitized→systemDefault LocalDateTime, GPS/остальные поля optional; duration не задаёт. Capture fallback upload time; metadata row только при наличии metadata fields.

**Applied to:** [03-data-model.md](03-data-model.md) metadata;[05-api.md](05-api.md) wire dictionary;[07-file-storage.md](07-file-storage.md) extraction;[09-state-model.md](09-state-model.md) states;[16-risks.md](16-risks.md) RISK-SRV-034/036.

## VER-SRV-020 — Что доказывают tests owner-delete и metadata failure

**Reason:** AMBIGUOUS

**Audit A:** A13: сценарии представлены как покрытие multi-reference deletion/EXIF failure.

**Audit B:** B13: имена тестов шире фактических assertions.

**Question:** Подтверждают ли выбранные tests именно эти production branches?

**Code inspected:**

- [src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java:730](../../../src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java#L730)
- [src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java:784](../../../src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java#L784)
- [src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java:876](../../../src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java#L876)

**Verified result:** Owner test загружает одинаковые bytes в одну default folder, duplicate возвращает одну identity. Non-owner test создаёт reference напрямую. Metadata test использует text bytes с image header, что не гарантирует extractor exception branch. Missing/foreign/physical tests проверяют конкретные404, не любой missing-ID сценарий. Production behavior взят из code выше; тесты не запускались и не менялись.

**Applied to:** [14-testing-current-state.md](14-testing-current-state.md) evidence limits;[19-source-traceability.md](19-source-traceability.md) counting/exclusions.

## VER-SRV-021 — Категоричные выводы о SQL plans

**Reason:** AMBIGUOUS

**Audit A:** A04 §6: lower(checksum) лишает запрос возможности использовать unique index; name IgnoreCase точно соответствует lower index.

**Audit B:** B04: prefix может применяться, exact plan не измерен.

**Question:** Есть ли в проверяемом запросе основания гарантировать plan?

**Code inspected:**

- [src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java:45](../../../src/main/java/ru/tbcarus/photocloudserver/repository/FileItemRepository.java#L45)
- [src/main/resources/db/changelog/table/13-file-api-operations.sql:6](../../../src/main/resources/db/changelog/table/13-file-api-operations.sql#L6)
- [src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql:18](../../../src/main/resources/db/changelog/table/14-file-item-user-folder-checksum.sql#L18)

**Verified result:** Query имеет user/folder equality + lower(checksum) IN; index user/folder/checksum, name index non-unique lower. Ни schema, ни имя derived method не доказывают execution plan/точное expression matching. Абсолютные выводы A исключены; fact index presence/absence сохранён. EXPLAIN не выполнялся, это не дополнительный OPEN продуктового contract.

**Applied to:** [04-database.md](04-database.md) queries;[16-risks.md](16-risks.md) RISK-SRV-031/035.

## VER-SRV-022 — Независимость root/temp overrides

**Reason:** B_ONLY

**Audit A:** A11: temp default обозначен как root/tmp без уточнения итогового override.

**Audit B:** B11/B16 O6: placeholder ссылается на environment STORAGE_ROOT, не property storage.root.

**Question:** За каким значением следует default temp-dir?

**Code inspected:**

- [src/main/resources/application.yml:41](../../../src/main/resources/application.yml#L41)
- [src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java:323](../../../src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java#L323)
- [src/main/java/ru/tbcarus/photocloudserver/service/storage/StoragePathResolver.java:15](../../../src/main/java/ru/tbcarus/photocloudserver/service/storage/StoragePathResolver.java#L15)

**Verified result:** Root и temp читаются отдельно; nested placeholder temp использует STORAGE_ROOT. Override только storage.root не обязан перемещать temp. Совпадение FS и mounts не проверяется приложением.

**Applied to:** [07-file-storage.md](07-file-storage.md) paths;[11-configuration.md](11-configuration.md) overrides;[16-risks.md](16-risks.md) RISK-SRV-022.
