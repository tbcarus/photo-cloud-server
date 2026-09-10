# 16. Пробелы и риски

Это оценочная часть. [RISK] обозначает возможное последствие подтверждённого control flow/DDL либо явно условного сценария; инциденты не воспроизводились. Severity: HIGH — возможная потеря данных/доступа, утечка credentials либо существенный отказ; MEDIUM — нарушение контракта, локальная целостность/эксплуатация; LOW — ограниченное влияние/навигация. Это приоритизация аудита, не CVSS.

## Functional gaps

| ID / classification | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| F1 [PARTIAL] | Reset email ведёт на501 page | EmailService.getEmailContext → PasswordController.getResetPasswordPage | Пользователь не завершит reset по обычной странице письма | HIGH |
| F2 [PARTIAL] | Resend/profile/settings routes — заглушки |7 controller mappings501 | Client UI может предлагать недоступное действие | MEDIUM |
| F3 [PARTIAL] | Sync только folder checksum pre-check | ChecksumSyncService, нет sessions/devices/delta model | Удаления/перемещения и состояние нескольких устройств не согласуются протоколом | MEDIUM |
| F4 [PARTIAL] | Video duration не извлекается | DrewFileMetadataExtractor только image; durationSec не задаёт | Поля media UI остаются null, capturedAt video = upload time | MEDIUM |
| F5 [RISK] | CAMERA не создаётся через GET root/children, а exists требует ID | FolderService.getOrCreateRoot vs getDefaultFolder; ChecksumExistsRequest | Первый pre-check автозагрузки не имеет cameraId без отдельного bootstrap решения | MEDIUM |

## Architectural risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| A1 [RISK] | Нет общей транзакции DB+FS | FileItemService move/copy до TransactionTemplate, delete FS после commit | Orphan/dangling references при аварии | HIGH |
| A2 [RISK] | Зависимость services от controller constants, repository от DTO | EmailService imports; FileItemRepository projection | Связанные изменения REST и внутренних компонентов | LOW |
| A3 [RISK] | UserService совмещает workflows, SMTP синхронный | UserService/EmailService | Задержки/отказы внешнего SMTP отражаются в register/reset | MEDIUM |

## Data integrity risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| D1 [RISK] | Destructive upgrade старой модели |10 DROP media_file CASCADE без INSERT/переноса | Потеря старых metadata, orphan bytes | HIGH |
| D2 [RISK] | Migration11/12/14 требуют чистых данных без preconditions |11 backfill+NOT NULL/unique;12 indexes;14 unique | Startup migration failure на existing DB | HIGH |
| D3 [RISK] | Checksum хранится в двух местах, без DB равенства | FileItem vs StoredObject; mapper и exists читают разные источники | DTO/check/precheck противоречат друг другу при drift | MEDIUM |
| D4 [RISK] | Имя файла проверяется до save без unique DB rule | ensureFileNameAvailable;13 non-unique lower-name index | Concurrent upload/rename создаёт одинаковые names вне CAMERA | MEDIUM |
| D5 [RISK] | RaceSafe folder catch внутри той же транзакции | FolderService.saveAndFlush → catch → requery; write @Transactional | Failed transaction/500 вместо обещаемого reread | MEDIUM |
| D6 [RISK] | Read-check-write folder tree без locks/version | moveFolder.ensureNotDescendant; SQL только self-parent CHECK | Concurrent opposing moves могут образовать цикл | HIGH |
| D7 [RISK] | DB FK не связывает owner с folder/object |10 FK по одиночным ID | Некорректные internal/manual rows допускают cross-owner links | MEDIUM |
| D8 [RISK] | Commit уже завершён до DTO map, cleanup ловит mapper runtime | upload/copy return fileItemMapper.toDto(saved) внутри cleanup try | DB row остаётся, final может быть удалён | MEDIUM |
| D9 [RISK] | Long extension не всегда помещает originalName в255 | FilenameSanitizer.limitOriginalNameWithExtension | DB constraint500 вместо нормализации/validation | MEDIUM |
| D10 [RISK] | Copy extension metadata не синхронизируется с новым именем | filename генерируется override name, fileExtension копируется source | Stored filename и extension column расходятся | LOW |
| D11 [RISK] | Одноразовость email-code без locking | check used → dirty update, нет @Version | Два параллельных подтверждения могут оба пройти | MEDIUM |
| D12 [RISK] | Нет транзакции всей регистрации/login | UserService отдельные repository saves | User без code; lastLoginAt обновлён при сбое token issue | MEDIUM |

Для D5 severity **MEDIUM**: это риск обработки конкурентного создания/rename, воспроизведение не проводилось.

## Security risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| S1 [RISK] | Секретные JSON/query и HTML пишутся в INFO | HttpLoggingFilter; EmailService.sendEmail | Пароли, JWT и email-коды доступны читателям логов | HIGH |
| S2 [RISK] | Enabled/banned проверяются только login | JwtAuthenticationFilter; UserService.refreshToken | Доступ отключённого пользователя продолжается до expiry/отзыва refresh | HIGH |
| S3 [RISK] | Password reset не отзывает existing tokens | EmailRequestService.resetPassword | Скомпрометированный refresh сохраняет доступ после смены пароля | HIGH |
| S4 [RISK] | Access не отзывается logout; race refresh/revoke | JwtService без blacklist/lock | Окно доступа после logout, возможно выдача нового access concurrent | MEDIUM |
| S5 [RISK] | Rate limiting отсутствует; helper не используется | register/forgot generateEmailRequest directly, login каждый раз INSERT | Перебор/SMTP abuse/рост таблицы токенов | HIGH |
| S6 [RISK] | Reset/registration раскрывают наличие email | unknown reset400, duplicate409 | Account enumeration | MEDIUM |
| S7 [RISK] | Full refresh token plaintext в DB без rotation | RefreshToken.token; refresh flow | Чтение DB даёт bearer credentials, refresh reusable до revoke/expiry | HIGH |
| S8 [RISK] | Email links строятся из incoming request host/scheme | EmailService.getEmailContext | При недоверенной proxy/Host конфигурации ссылка может иметь неправильный origin | MEDIUM |
| S9 [RISK] | JWT-подобные literals в tracked .http | docs/http-tests/api-smoke-tests.http | Копирование credential-like данных, ложная уверенность в пригодности примеров | MEDIUM |
| S10 [RISK] | Password length4..20 без complexity | DTO Register/Login/Reset; @Pattern commented | Допускаются слабые пароли; длинные passphrases не принимаются | MEDIUM |
| S11 [RISK] | Неиспользуемый confirmEmail не связывает code owner/email | EmailRequestService.confirmEmail | При будущем прямом подключении метода можно подтвердить не владельца code; текущий HTTP не использует | LOW |
| S12 [RISK] | Нет явного TLS/CORS config | application.yml/SecurityConfig | Требования к browser/proxy/транспорту остаются вне репозитория; live HTTPS неизвестен | MEDIUM |

## Storage risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| T1 [RISK] | Нет reconciliation/cleanup after crash | FileItemService catches, scheduler отсутствует | Накопление orphan/temp без автоматического обнаружения | HIGH |
| T2 [RISK] | Existing metadata не проверяет bytes | upload duplicate, exists query | Ложный успешный повтор upload не восстанавливает missing file | HIGH |
| T3 [RISK] | Delete FS failure только log после DB | deleteFileForCurrentUser |204 при оставшихся bytes; по fileId cleanup уже не повторить | MEDIUM |
| T4 [RISK] | Partial copy/move cleanup ограничен flags | copied/movedToFinal после success | Оставшийся partial target при IO failure | MEDIUM |
| T5 [RISK] | Atomic move fallback, fsync нет | moveTempToFinal; FileUtils | Crash consistency не гарантируется даже после обычного IO success | MEDIUM |
| T6 [RISK] | Лексический startsWith не realpath | StoragePathResolver.resolve | Локальные symlink/reparse-point подмены не покрыты проверкой | MEDIUM |
| T7 [RISK] | Copy доверяет metadata source | createCopiedStoredObjectAndFileItem | При повреждении bytes копия унаследует неверные checksum/size | MEDIUM |
| T8 [RISK] | Нет общей quota/disk-space admission | Только storage.maxFileSizeBytes | Заполнение диска многими корректными uploads | MEDIUM |

Для T5–T8 severity **MEDIUM**; это условия эксплуатации/аварий, не подтверждённые инциденты.

## API consistency risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| C1 [INCONSISTENCY] | Move checksum conflict500, copy409, upload200 | FileItemService методы +14 unique + handler | Клиент может бессмысленно retry500 и неверно трактовать duplicate | HIGH |
| C2 [INCONSISTENCY] | ErrorResponse покрывает не все ошибки | Нет IO/generic handler;501 Map | Error parser клиента должен переносить неоднородное тело | MEDIUM |
| C3 [INCONSISTENCY] | Public endpoint rejects malformed Bearer | JwtAuthenticationFilter без public exclusions | Refresh с expired access header получает401 до проверки refresh | MEDIUM |
| C4 [INCONSISTENCY] | Ручная документация отстаёт | http-tests README/.http/curl, TODO | Ошибочное понимание routes/delete/reset/limits | MEDIUM |
| C5 [RISK] | Locale/timezone local datetime без offset | EXIF/JVM LocalDateTime и DTO | Разный порядок/интерпретация времени клиентами в разных зонах | MEDIUM |
| C6 [RISK] | Filename attachment без filename* encoding | FileController.downloadFile | Unicode имена могут по-разному интерпретироваться HTTP-клиентами | MEDIUM |

C3–C6 severity **MEDIUM**. У C3 поведение подтверждено; риск — ломание refresh interceptor.

## Maintainability risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| M1 [INCONSISTENCY] | Repository ID Integer vs Long | EmailRequestRepository, RefreshTokenRepository | Ошибочный тип inherited CRUD methods | MEDIUM |
| M2 [INCONSISTENCY] | Tests named wider than assertions | ownerDelete*, cannotRename*, metadataExtractionFailure* | Ложная оценка покрытия после изменений | MEDIUM |
| M3 [RISK] | Неиспользуемые exceptions/methods/константы | TickerRequestException, EntityAlreadyExistException, reverse mapper, helpers | Сложнее выделить действующий контракт | LOW |
| M4 [INCONSISTENCY] | Entity mappings менее строгие, чем DDL | User lengths, FileMetadata precision, refresh TEXT | Поведение ограничений определяется SQL, не DTO/entity декларациями | MEDIUM |
| M5 [RISK] | Письмо reset содержит branding другого приложения | passwordResetTemplate.html | Пользователь не доверяет reset-письму | LOW |

## Operational risks

| ID | Описание | Доказательство | Возможное последствие | Severity |
| --- | --- | --- | --- | --- |
| O1 [RISK] | Download response полностью cached logging wrapper | HttpLoggingFilter | Память пропорциональна параллельным размерам ответов; возможен OOM | HIGH |
| O2 [RISK] | Неограниченные page size и checksum list | FileController.getUserFiles/getChecksums | Тяжёлые запросы/крупный JSON и memory pressure | MEDIUM |
| O3 [RISK] | SMTP debug, нет explicit timeout/retry | MailConfig | Шум/чувствительные diagnostics, зависание/отказ request flow | MEDIUM |
| O4 [RISK] | Нет health/readiness/metrics/tracing/backup policy | build/config/controllers | Ошибки диска/DB/cleanup могут быть поздно замечены | MEDIUM |
| O5 [RISK] | Refresh lookup full token без index; email history без composite |02/03 SQL, repositories | Деградация при росте данных; планы не измерены | MEDIUM |
| O6 [RISK] | Root/env/temp override могут разойтись | application.yml nested environment placeholders | Temp и final на разных FS, потеря ожидаемого atomic move | MEDIUM |
| O7 [RISK] | Runtime errors после удаления user не mapped401 | loadUserByUsername Optional.get | Неожиданный500/error dispatch для ранее valid JWT | MEDIUM |

## Предложения по развитию — вне As-Is

Предложения намеренно ограничены проверкой уже обнаруженных рисков: воспроизвести конкурентные и аварийные сценарии D5/D6/T4; проверить реальные migration prerequisites на копии данных; установить согласованный client contract для C1/C2/F5. Эти действия **не выполнены** в аудите и не изменяют перечисленное поведение. Новая архитектура и массовый рефакторинг не предлагаются.

## Индекс доказательств

Все имена методов/классов выше относятся к [service](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [security filters](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter), [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [repositories](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/repository), [migrations](C:/projects/photo-cloud-server/src/main/resources/db/changelog/table), [tests](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver), [документации](C:/projects/photo-cloud-server/docs). Подробные branch-by-branch доказательства находятся в04/06/07/10/13/14.
