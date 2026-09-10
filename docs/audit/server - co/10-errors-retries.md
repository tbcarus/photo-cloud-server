# 10. Ошибки, повторные попытки, идемпотентность

## Источники и общая форма

[CONFIRMED] [GlobalExceptionHandler](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/GlobalExceptionHandler.java) — @RestControllerAdvice с конкретными @ExceptionHandler. [ErrorResponse](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/exception/dto/ErrorResponse.java) содержит UUID id, ErrorCode code, String message, Map<String,String> fieldErrors. Не-validation response обычно fieldErrors=null; tests это проверяют. id создаётся заново на ошибку, не переданный trace ID.

~~~json
{"id":"00000000-0000-0000-0000-000000000000","code":"VALIDATION_ERROR","message":"Validation failed","fieldErrors":{"email":"Email must be valid"}}
~~~

Пример искусственный, не значение лога. Validation messages по Bean Validation могут зависеть от locale; несколько ошибок одного field перезаписываются HashMap.put, выбор одной не детерминирован контрактом.

## Иерархия

[CONFIRMED] Все 17 custom exception classes в exception напрямую наследуют RuntimeException. Domain base exception отсутствует. GlobalExceptionHandler, ErrorType enum и два DTO не являются throwable. Errors от Spring validation/DAO/multipart/JJWT/Java IO существуют отдельно.

| Exception / source | HTTP / code | Message/body | Retryability |
| --- | --- | --- | --- |
| [CONFIRMED] BadRegistrationRequest, email code service |400 BAD_REGISTRATION_REQUEST | ErrorType.title; в доступном confirm обычно «Запись не найдена» | Нет для того же кода |
| [CONFIRMED] InvalidCredentialsException, login |401 INVALID_CREDENTIALS | Invalid email or password | Повтор без исправления бесполезен |
| [CONFIRMED] DuplicateEmailException, register |409 CONFLICT | Email is already registered | Нет |
| [CONFIRMED] InvalidRefreshTokenException |401 INVALID_REFRESH_TOKEN | Invalid refresh token | С этим token нет |
| [CONFIRMED] TokenRevokedException |403 REFRESH_TOKEN_REVOKED | token revoked | Нет |
| [CONFIRMED] RefreshTokenOwnershipException |403 REFRESH_TOKEN_OWNERSHIP_ERROR | Refresh token does not belong to current user | Исправить пару account/token |
| [CONFIRMED] RefreshTokenNotFoundException |404 REFRESH_TOKEN_NOT_FOUND | Refresh token not found | Не blind retry |
| [CONFIRMED] EntityNotFoundException |400 BAD_REQUEST | exception.message (например User <email> not found) | Не исправит неизвестный email |
| [CONFIRMED] FileItemNotFoundException |404 FILE_ITEM_NOT_FOUND | File item <id> not found | Read/reconcile; missing bytes требуют server-side решения |
| [CONFIRMED] FolderNotFoundException |404 NOT_FOUND | Folder <id> not found | Проверить target/account |
| [CONFIRMED] FileConflictException / FolderConflictException |409 CONFLICT | service message: name/checksum/reserved conflict | Изменить input/target; не повторять без изменения |
| [CONFIRMED] FolderOperationException |400 BAD_REQUEST | system/nonempty/cycle/name message | Изменить операцию |
| [CONFIRMED] FileSizeLimitExceededException |413 FILE_TOO_LARGE | File size exceeds limit: <max> bytes | Те же bytes не помогут |
| [CONFIRMED] MaxUploadSizeExceededException |413 FILE_TOO_LARGE | Multipart request exceeds configured limit | Уменьшить request/file |
| [CONFIRMED] IllegalArgumentException |400 BAD_REQUEST | exception.message; empty file, invalid page, batch, path | Исправить input; internal path may need server repair |
| [CONFIRMED] DataIntegrityViolationException |500 DATABASE_CONSTRAINT_VIOLATION | Database constraint violation | По причине: checksum conflict move повтором не исправляется |
| [CONFIRMED] HttpMessageNotReadableException |400 BAD_REQUEST | Malformed or missing request body | Исправить JSON/body |
| [CONFIRMED] MissingServletRequestParameterException |400 BAD_REQUEST | Required request parameter is missing | Добавить query |
| [CONFIRMED] MethodArgumentNotValidException |400 VALIDATION_ERROR | Validation failed + fieldErrors from field path | Исправить поля |
| [CONFIRMED] ConstraintViolationException |400 VALIDATION_ERROR | Validation failed; последний segment propertyPath как key | Исправить параметры |
| [UNUSED] FileNotFoundException |404 NOT_FOUND | exception.message; handler есть, production throw не найден | Нет активного сценария |
| [UNUSED] TickerRequestException |400 BAD_REQUEST | exception.message; domain применения не найден | Нет активного сценария |
| [UNUSED] EntityAlreadyExistException |Не mapped | Собственный message/entityName, callers нет | Не контракт |
| [CONFIRMED] JSON entry point |401 UNAUTHORIZED | Unauthorized: access token expired or invalid | Refresh без invalid Bearer при пригодном refresh |
| [CONFIRMED] JSON denied handler |403 FORBIDDEN | Forbidden | Повтор без изменения прав не поможет |

[UNUSED] ErrorCode.INTERNAL_ERROR объявлен, но не используется обработчиком; это не гарантия единого500. ErrorType.PERIOD_EXPIRED/DO_NOT_MATCH/WRONG_LENGTH не используются в действующих email validation branches. TOO_MUCH_REPEAT_REQUESTS достижим только через неиспользуемый checkAndGenerateCode.

## Ошибки вне единого контракта

[CONFIRMED] IOException upload/copy/download объявляются наружу; нет специального handler или catch-all Exception. MailException/runtime template errors, NoSuchElementException user lookup, LazyInitializationException и прочие runtime не покрыты общим advice. Ошибки фильтра вне JWT catches не перехватываются MVC advice. Missing multipart part, method/type mismatch, unsupported media type и no-route обрабатываются framework/security, а не данным JSON mapper.

[INFERRED] Возможны стандартные framework400/404/405/415/500 и иная форма error body, включая error dispatch/security. Без runtime проверки конкретный body и все secondary statuses не установлены. «Каждая ошибка — ErrorResponse» неверно.

[CONFIRMED] Все7 заглушек501 используют Map(message), не ErrorResponse. Content-Type у обработанных JWT errors JSON UTF-8, у controlled REST DTO обычная JSON serialization.

## Особое восстановление и скрытые ошибки

[CONFIRMED] Upload ловит DataIntegrityViolation после move: удаляет собственные bytes, requery matching checksum, если найден —200, иначе500. Это не общий retry repository: реального повторного INSERT нет. Unit test моделирует catch, не real parallel transaction.

[CONFIRMED] Folder create/rename/move пытаются переводить DataIntegrityViolation в409, root/system creation пытается requery. [RISK] Exception может проявиться на commit после save, за пределами локального catch; после saveAndFlush в failed PostgreSQL transaction requery ненадёжен. Последовательные tests не доказывают race-safe.

[CONFIRMED] Metadata extraction catch-all → WARN и успешный upload с metadata=null/частично отсутствующими полями. Delete bytes IOException после DB → ERROR и204. Temp/final cleanup IOException → ERROR без изменения исходной ошибки. Эти операции не запрашиваются повторно сервером.

## Матрица повторов

| Операция | Безопасность повторного эффекта | Практический предел |
| --- | --- | --- |
| [CONFIRMED] GET metadata/list/checksums/children/download | Read-only в прикладной логике | Данные могут измениться между запросами |
| [CONFIRMED] GET root | Lazy create с unique ROOT | GET может писать; race handling не доказан |
| [CONFIRMED] POST checksums/exists | Read-only | Не reservation; размер raw list не более500 |
| [INFERRED] Upload тех же bytes в ту же folder | Повтор возвращает existing при неизменном DB state | Нет repair missing bytes; полный upload повторяется |
| [CONFIRMED] Rename/move того же id/target | Не создаёт новый объект | Concurrent changes/409/500 могут изменить результат |
| [CONFIRMED] Copy | Не replay того же200 | После удачи повтор409; нужно сверять target |
| [CONFIRMED] Delete | Целевой эффект «нет row» повторим | Первый204, следующий404; orphan FS не очищается повтором |
| [CONFIRMED] Login | Не idempotent | Каждый success новый refresh row |
| [CONFIRMED] Refresh | Token не consumed | Не unique-result; срок refresh не продлевается |
| [CONFIRMED] Logout | Revocation повторима | revokedAt меняется; нужен ещё valid access |
| [CONFIRMED] Register/reset-request | Не idempotent | Register duplicate409, reset-request новые code/email |
| [CONFIRMED] Confirm code | Одноразовая бизнес-проверка | Повтор400, нет idempotent success cache |

[INFERRED] Временные network/DB/IO ошибки допускают ограниченный повтор только с учётом операции и неизвестного результата. Сервер не возвращает Retry-After, retry count или retryable flag; exponential backoff/авторетрай не реализованы. Ошибки500 не следует автоматически считать временными: constraint violation move детерминирован.

[CONFIRMED] Idempotency-Key, request ID persistence, optimistic @Version, conditional mutation via ETag, transactional outbox, background retry queue не найдены.
