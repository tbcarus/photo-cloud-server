# Ошибки, повторные запросы и идемпотентность

## Формат и границы handler

[CONFIRMED] ErrorResponse: id UUID, code ErrorCode, message String, fieldErrors Map<String,String>. UUID генерируется заново в каждом handler, не является operation/idempotency ID. Для обычных ошибок fieldErrors=null; для validation — map, message='Validation failed'. Нет timestamp/path/retryAfter. Источники: ErrorResponse.java, ErrorCode.java, GlobalExceptionHandler.java.

[CONFIRMED] @RestControllerAdvice содержит конкретные @ExceptionHandler, но **не** catch-all Exception/RuntimeException/IOException. Security handler работает отдельно до MVC. 501 выдаются контроллерами как {message}, success registration confirm — plain string. Поэтому клиент не должен предполагать ErrorResponse у любого не-2xx ответа.

## Полная матрица обработанных исключений

Все строки [CONFIRMED] — GlobalExceptionHandler, кроме явно отмеченных filter handlers. E(code,message) = {id:новый UUID,code,message,fieldErrors:null}. V(map) = {id,code:VALIDATION_ERROR,message:'Validation failed',fieldErrors:map}.

| Источник / exception | HTTP | Body | Retryability [INFERRED] |
| --- | --- | --- | --- |
| DTO MethodArgumentNotValidException | 400 | V(field→message) | Исправить request; same body не поможет |
| Method constraint ConstraintViolationException | 400 | V(last property segment→message) | Исправить parameter |
| HttpMessageNotReadableException | 400 | E(BAD_REQUEST,'Malformed or missing request body') | Исправить JSON/обязательный body |
| MissingServletRequestParameterException | 400 | E(BAD_REQUEST,'Required request parameter is missing') | Добавить параметр |
| IllegalArgumentException: empty file, pagination, batch cap/path/name | 400 | E(BAD_REQUEST,e.message) | Исправить вход/config; не blind retry |
| BadRegistrationRequest: invalid/used/expired/wrong type code | 400 | E(BAD_REGISTRATION_REQUEST,ErrorType.title) | Тот же code не поможет; неизвестный исход confirm проверить отдельно |
| EntityNotFoundException: forgotPassword unknown user | 400 | E(BAD_REQUEST,e.message) | Изменить email/аккаунт |
| FolderOperationException: system/nonempty/cycle | 400 | E(BAD_REQUEST,e.message) | Изменить действие/состояние |
| TickerRequestException | 400 | E(BAD_REQUEST,e.message) | [UNUSED] Throw site не найден |
| InvalidCredentialsException | 401 | E(INVALID_CREDENTIALS,'Invalid email or password') | Не refresh; исправить credentials/state |
| InvalidRefreshTokenException | 401 | E(INVALID_REFRESH_TOKEN,'Invalid refresh token') | Тот же refresh непригоден |
| Missing/invalid/expired access: JsonAuthenticationEntryPoint | 401 | E(UNAUTHORIZED,'Unauthorized: access token expired or invalid') | Получить access через пригодный refresh, повторить операцию с учётом её эффектов |
| TokenRevokedException | 403 | E(REFRESH_TOKEN_REVOKED,'token revoked') | Refresh не восстановится повтором |
| RefreshTokenOwnershipException | 403 | E(REFRESH_TOKEN_OWNERSHIP_ERROR,'Refresh token does not belong to current user') | Не смешивать аккаунты/tokens |
| JsonAccessDeniedHandler | 403 | E(FORBIDDEN,'Forbidden') | Повтор без изменения прав не поможет |
| RefreshTokenNotFoundException | 404 | E(REFRESH_TOKEN_NOT_FOUND,'Refresh token not found') | Уточнить локальную сессию; logout row отсутствует |
| FileItemNotFoundException | 404 | E(FILE_ITEM_NOT_FOUND,'File item <id> not found') | Сверить ID/состояние; download может означать missing bytes |
| FolderNotFoundException | 404 | E(NOT_FOUND,'Folder <id> not found') | Сверить ID/аккаунт |
| FileNotFoundException | 404 | E(NOT_FOUND,e.message) | [UNUSED] Текущий file service использует FileItemNotFoundException |
| DuplicateEmailException | 409 | E(CONFLICT,'Email is already registered') | Не повторять register тем же email |
| FileConflictException | 409 | E(CONFLICT,e.message) | Name или copy checksum conflict; изменить запрос/сверить результат |
| FolderConflictException | 409 | E(CONFLICT,e.message) | Name/reserved conflict; изменить запрос |
| FileSizeLimitExceededException | 413 | E(FILE_TOO_LARGE,'File size exceeds limit: <bytes> bytes') | Same file не поможет |
| MaxUploadSizeExceededException | 413 | E(FILE_TOO_LARGE,'Multipart request exceeds configured limit') | Уменьшить request |
| DataIntegrityViolationException | 500 | E(DATABASE_CONSTRAINT_VIOLATION,'Database constraint violation') | Может быть детерминированный constraint, не обязательно transient |

[CONFIRMED] ErrorType содержит PERIOD_EXPIRED, NOT_FOUND, DO_NOT_MATCH, WRONG_LENGTH, TOO_MUCH_REPEAT_REQUESTS. Активный code validator использует NOT_FOUND для всех причин, а не PERIOD_EXPIRED. TOO_MUCH_REPEAT_REQUESTS находится в неиспользуемом checkAndGenerateCode. INTERNAL_ERROR enum есть, producer не найден. EntityAlreadyExistException не имеет текущего throw site/handler.

[CONFIRMED] Все 17 custom exception классов прямо наследуют RuntimeException; общей domain exception base нет. Часть message getter генерирует Lombok @Getter вместо передачи super(message), но handler вызывает getMessage(). TokenRevokedException хранит raw token в поле, handler его в body не включает.

## Непокрытые и подавленные ошибки

| Сценарий | Реальный участок | Граница достоверности |
| --- | --- | --- |
| IO temp/MIME/move/copy/download | FileItemService throws IOException | Нет собственного HTTP mapping; framework/container response не проверен |
| Missing multipart part, wrong Long/int, unsupported HTTP method/type | MVC binding | Отдельных handlers здесь нет; нельзя обещать fieldErrors/ErrorResponse |
| User по access sub удалён | UserService.loadUserByUsername Optional.get | NoSuchElementException не ловится JWT catches |
| DB connectivity/deadlock/transaction rollback | repositories/transaction manager | Handler только DataIntegrityViolationException, не весь DataAccessException |
| SMTP MailException/template runtime | EmailService/UserService | Catch только MessagingException; user/code уже могли сохраниться |
| MessagingException с null cause | log e.getCause().getMessage() | Catch сам может бросить NullPointerException |
| Metadata parser exception | DrewFileMetadataExtractor | WARN + empty result, upload продолжается |
| Cleanup/delete physical IOException | FileItemService.deleteIfExists/deleteFile... | ERROR log; cleanup не повторяется; delete возвращает204 |

[INFERRED] Для непокрытых exceptions возможен framework 5xx, а error dispatch через защищённый /error способен изменить наблюдаемый ответ. Не фиксируем неподтверждённый универсальный status/body для этой группы.

## Повторы по операциям

| Операция | Безопасность повтора / unknown outcome |
| --- | --- |
| GET list/card/download/checksums, POST exists | Read-only в бизнес-данных; повтор возможен, snapshot может отличаться |
| GET root | Может создать ROOT; sequential повтор возвращает тот же ID |
| Upload | Повтор тех же байтов в ту же существующую папку под тем же User возвращает existing; после move/delete исходной записи может создать новую |
| Rename/move | Повтор абсолютного имени/целевого ID обычно сходится; перед повтором после неизвестного результата GET card |
| Copy | Повтор после успешного commit возвращает409; existence не возвращает copied ID, нужен target list |
| Delete file/folder | После успешного удаления повтор404; это не доказательство сбоя первого запроса |
| Register | После user save повтор409; письмо могло не уйти |
| Login | Каждый retry создаёт новый refresh, если предыдущий login прошёл |
| Refresh | Разрешён повтор пригодного refresh, expiry не продлевается |
| Logout variants | Отзыв по состоянию повторяем, но одиночный logout меняет revokedAt повторно, bulk захватит новые login rows |
| Password reset request | Повтор создаёт новый code/письмо |
| Code confirm/reset | Одноразовый code; повтор400 даже после успеха |
| 501 stub | Повтор не добавляет реализацию |

[CONFIRMED] Нет Idempotency-Key, request journal, operation-result endpoint, server retry/backoff, Retry-After или retry queue. [INFERRED] Сеть/5xx могут быть временными, но автоматический безусловный повтор любой mutation не следует из API. 409 duplicate upload не является normal success marker: normal upload duplicate — 200.

