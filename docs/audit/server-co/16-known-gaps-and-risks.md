# Подтверждённые пробелы и потенциальные риски

Все позиции ниже — [RISK] оценка последствий. «Основание» указывает код/SQL/тест; это не утверждение о произошедшей утечке, потере данных или текущей неисправности production. Severity HIGH/MEDIUM/LOW относительна влиянию на доступ, данные и восстановление; количественная вероятность не измерялась. Runtime-воспроизведения не выполнялись.

## Functional gaps

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| AUTH-01 | MEDIUM | EmailService ведёт PASSWORD_RESET на PasswordController.getResetPasswordPage, ответ501 | Пользователь не завершит browser recovery по письму |
| FUNC-01 | MEDIUM | Register/Password resend501; generateEmailRequest без delivery state | Недоставленное activation письмо оставляет disabled user без resend API |
| FUNC-02 | LOW | UserController profile edit/settings501 | Клиентский UI может обещать несохраняемые изменения |
| FUNC-03 | MEDIUM | ChecksumSyncService — только logical exists; Device/SyncSession/tombstone нет | Нельзя восстановить двустороннюю синхронизацию или удаления из данного протокола |
| FUNC-04 | LOW | DrewFileMetadataExtractor only image; durationSec never set | Видео timestamps fallback на upload, metadata duration отсутствует |
| FUNC-05 | LOW | FileItem.deletedAt unused; FolderService empty-only delete | Поля/названия модели не означают реализованную корзину/recursive delete |

## Architectural risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| ARCH-01 | MEDIUM | EmailService зависит от HTTP RequestContext и controller constants | Вызов вне servlet context упадёт; URL связан с transport layout |
| ARCH-02 | MEDIUM | UserService register/login/forgotPassword делает несколько commits без общей transaction; SMTP отдельно | Частично сохранённый результат при HTTP failure |
| ARCH-03 | MEDIUM | Service/domain знают Resource/UserDetails, FileItemService совмещает FS/DB/metadata | Изменение одного boundary требует учёта нескольких обязанностей; не доказанный functional defect |
| ARCH-04 | MEDIUM | JPA LAZY и service возвращают detached graph при open-in-view=false; Graph содержит лишь нужные отношения | Новый mapper/access к не загруженным полям может дать late exception; текущие graphs снижают риск |

## Data integrity risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| DATA-01 | HIGH | SQL10 DROP TABLE media_file CASCADE без backfill | Upgrade удаляет исторические metadata; bytes остаются без связей |
| DATA-02 | MEDIUM | FileItem.checksum денормализован; mapper/full list читают SO, exists/unique читают FileItem; SQL14 не добавляет consistency trigger | Ответы list и exists расходятся после внешней/будущей неверной записи; test fileItemDtoReturnsPhysicalFieldsFromStoredObject демонстрирует независимость |
| DATA-03 | MEDIUM | FolderService createRootRaceSafe/createSystemChildRaceSafe catch saveAndFlush и query в той же @Transactional области | Предполагаемая race recovery может не сработать при rollback-only/DB aborted state; не проверено concurrent test |
| DATA-04 | HIGH | FolderService.ensureNotDescendant проверяет текущее дерево, нет locks/@Version; SQL12 только self-parent check | Opposing concurrent moves могут создать цикл, дальнейший parent traversal может зациклиться |
| DATA-05 | MEDIUM | Имя FileItem проверяется exists до save; SQL13 index nonunique, CAMERA исключение только service | Два concurrent разных файла могут занять одно имя вне CAMERA |
| DATA-06 | MEDIUM | FileItem/Folder/SO FK не связывают владельцев composite FK | Внешние writes способны создать cross-user references; доступ и delete начинают следовать чужим descriptor |
| DATA-07 | HIGH | SQL11 SET NOT NULL после backfill только из linked FileItem; SQL14 unique на существующих rows | Upgrade может остановиться на неполных/duplicate данных; preflight SQL14 имеется, автоисправления нет |
| DATA-08 | MEDIUM | Entity timestamps/SQL TIMESTAMP без offset, EXIF через systemDefault; offset policy не задана | Разные JVM timezone дают неоднозначные capture/expiry times |
| DATA-09 | MEDIUM | EmailRequest used/code checks без lock/version | Конкурентные code-consume запросы могут оба пройти до commit; одноразовость не сериализована |

## Security risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| SEC-01 | HIGH | HttpLoggingFilter INFO bodies, EmailService INFO context+HTML | Password/JWT/email code и private data доступны читателю логов |
| SEC-02 | HIGH | UserService.login проверяет enabled/banned; JwtAuthenticationFilter/JwtService.refreshAccessToken не проверяют | После блокировки ранее выданные refresh/access позволяют продолжать доступ |
| SEC-03 | HIGH | Reset password не отзывает токены; logout отзывает только refresh | Ранее украденный refresh переживает password reset; access переживает logout до exp |
| SEC-04 | MEDIUM | refresh_token.token plaintext TEXT, нет rotation/token family/reuse detection | Компрометация БД или token даёт повторный refresh до exp/revoke |
| SEC-05 | MEDIUM | Нет rate limiting; forgotPassword напрямую generateEmailRequest; passwords4..20 | Повторные login/reset запросы создают нагрузку/письма и новые rows, перебор ограничен лишь внешней средой |
| SEC-06 | MEDIUM | Forgot password unknown email400, register duplicate409 | API позволяет различать существование аккаунта |
| SEC-07 | MEDIUM | Email link base = request scheme/serverName/port, canonical public origin отсутствует | При неподходящем Host/proxy handling письмо может содержать неверный/контролируемый origin; зависит от deployment |
| SEC-08 | MEDIUM | JwtService refresh read/revoke независимы | Concurrent refresh может выдать access на основе ещё неотозванной snapshot row |
| SEC-09 | MEDIUM | Static token/code/credentials-like values в api-smoke-tests.http | Риск распространения чувствительных примеров/неверного повторного использования; валидность не проверялась |
| SEC-10 | LOW | EmailRequestService.confirmEmail(email,code) не проверяет owner | Сейчас [UNUSED], но подключение метода к endpoint изменит security boundary |
| SEC-11 | MEDIUM | Непустые arbitrary files разрешены; MIME detector не scanner | Можно хранить вредоносные байты; анализа/обезвреживания нет, насколько это допустимо — продуктовый вопрос |

## Storage risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| STORE-01 | HIGH | Final move до DB commit, delete bytes после DB commit, Java catch cleanup only | Crash оставляет orphan bytes/records; periodic reconciliation отсутствует |
| STORE-02 | MEDIUM | Files.copy flag copied=true только после return; fallback move flag аналогично | Partial target после IOException может не очищаться |
| STORE-03 | HIGH | Cleanup catch охватывает DTO mapping после TransactionTemplate.execute | Mapping runtime error после commit может удалить байты уже сохранённой записи |
| STORE-04 | HIGH | Exists/duplicate читают БД, getDownloadResource лишь exists/readable, checksum verify/repair нет | Lost/corrupt bytes не обнаруживаются pre-check; duplicate upload не восстанавливает файл |
| STORE-05 | MEDIUM | delete IOException логируется и возвращается204 | Клиент считает удаление полным, orphan занимает место; повтор404 не очищает его |
| STORE-06 | MEDIUM | Same content в другой папке/copy → новый physical object; квоты/cleanup нет | Рост disk consumption; глобального dedup ожидать нельзя |
| STORE-07 | MEDIUM | FilenameSanitizer.limitOriginalNameWithExtension сохраняет чрезмерно длинный suffix; UTF16 length vs OS bytes | Long extension/Unicode filename может вызвать DB/FS error вместо нормализованного результата |
| STORE-08 | MEDIUM | StoragePathResolver lexical startsWith, no realpath/symlink policy | Локально подменённый junction/symlink может перенаправить путь вне root; HTTP caller напрямую symlink не создаёт |
| STORE-09 | LOW | Copy сохраняет source fileExtension, новое имя может задать иной extension | Descriptor extension и generated filename suffix расходятся без изменения MIME/content |

## API consistency risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| API-01 | MEDIUM | moveFileForCurrentUser проверяет имя, не checksum; SQL14 unique; global constraint handler500 | Детерминированный duplicate выглядит transient server error |
| API-02 | MEDIUM | Copy precheck409, race constraint не переводится в conflict; Folder save error может возникнуть на flush после try | Номинально одинаковый конфликт даёт разные HTTP статусы |
| API-03 | MEDIUM | GlobalExceptionHandler без catch IOException/Exception, filter exceptions отдельно | Универсальный client ErrorResponse parser может ломаться |
| API-04 | MEDIUM | Invalid Bearer обрабатывается даже на public endpoints | Refresh/login, посланный с истёкшим access, не достигает своего обработчика |
| API-05 | LOW | originalName в rename request, originalFilename в response | Ошибка client DTO mapping без явного различения |
| API-06 | MEDIUM | GET /files/checksums не содержит folderId, не paginated; list offset snapshot не фиксирован | Неверный folder-specific dedup и пропуски/повторы при concurrent mutation |
| API-07 | LOW | getRoot lazy writes; POST create/upload/copy200; copy повтор409; delete повтор404 | Клиенту нельзя выводить идемпотентность только из method/status |

## Maintainability risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| MAINT-01 | MEDIUM | RefreshTokenRepository/EmailRequestRepository Integer ID, entities Long | Ошибка future inherited CRUD usage, типы не отражают схему |
| MAINT-02 | LOW | ConfigUtil/EmailRequest/literals duplicate expiry/max attempts | Изменение одного значения не обновит весь flow |
| MAINT-03 | LOW | Old DateUtil helpers/legacy exception classes, reset template с иным брендом | Ложные выводы о поддержанных features/ошибках и путаница пользователю |
| MAINT-04 | MEDIUM | Названия ownerDelete/metadataExtractionFailure tests шире assertions/fixture | Coverage создаёт ложное ощущение проверенных сценариев |
| MAINT-05 | LOW | Дублируемые folder indexes, PostgreSQL dependency declaration | Лишние точки сопровождения; реальный performance effect не измерен |
| MAINT-06 | MEDIUM | Документы расходятся: smoke26 vs routes36, старый порядок delete/storageKey, будущий copy | Следующий клиентский аудит может восстановить неверный контракт |

## Operational risks

| ID | Severity | Описание / основание | Возможное последствие |
| --- | --- | --- | --- |
| OPS-01 | HIGH | ContentCachingResponseWrapper на любом download, getContentAsByteArray перед copy | Большой heap consumption и потеря streaming-свойств при concurrent downloads |
| OPS-02 | MEDIUM | File list size без upper cap, checksum full list без paging | Большие SQL/DTO/response allocations |
| OPS-03 | MEDIUM | SMTP inline, собственных SMTP timeouts не задано; runtime mail exceptions не пойманы | Долгие/ошибочные auth requests при сохранённых user/code |
| OPS-04 | MEDIUM | Метрики/readiness/audit trail/reconciliation не найдены | Ошибки disk после204 и рост orphan могут долго оставаться незаметными |
| OPS-05 | MEDIUM | Storage root относительный, deployment topology/backup не описаны кодом | Ошибочный cwd/mount или несогласованный restore нарушает DB↔bytes |
| OPS-06 | MEDIUM | Refresh/email rows не очищаются; refresh token lookup без token index | Рост таблиц и стоимости auth lookup со временем |
| OPS-07 | MEDIUM | Endpoint access sub missing User → Optional.get outside JWT catches | Вместо управляемого401 возможен framework failure |
| OPS-08 | LOW | HTTP filter logging/copy без finally | Исключение downstream лишает части diagnostic записи; handler UUID не связан с log |
| OPS-09 | MEDIUM | Нет подтверждённого deployment test/DB state; найдены лишь старые unit XML | Нельзя доказать readiness текущей версии по репозиторию |

## Предложения по развитию (не As-Is)

Новые архитектуры, миграции и исправления в этом аудите не предлагаются и не выполняются. Практическое продолжение — отдельно согласованное воспроизведение SEC-02/API-01/DATA-03/DATA-04/STORE-02/STORE-03/OPS-01 в изолированном окружении и сопоставление реальной схемы с этим отчётом. Это предложение проверки рисков, не описание существующей функции и не разрешение менять production.

