# Ошибки и восстановление

## Error response и mapping

Обработанные REST/security ошибки используют `{id:UUID, code:string, message:string, fieldErrors:map|null}`. UUID создаётся на каждый ответ, не является входным request/trace ID. Field errors присутствуют для validation; иначе null. Сообщения частично русские, частично английские. Несколько нарушений одного поля записываются в map с перезаписью, без закреплённого приоритета сообщения.

| Exception / source | HTTP / code | Message/body |
| --- | --- | --- |
| BadRegistrationRequest, email code service | 400 BAD_REGISTRATION_REQUEST | ErrorType.title; в доступном confirm обычно «Запись не найдена» |
| InvalidCredentialsException, login | 401 INVALID_CREDENTIALS | Invalid email or password |
| DuplicateEmailException, register | 409 CONFLICT | Email is already registered |
| InvalidRefreshTokenException | 401 INVALID_REFRESH_TOKEN | Invalid refresh token |
| TokenRevokedException | 403 REFRESH_TOKEN_REVOKED | token revoked |
| RefreshTokenOwnershipException | 403 REFRESH_TOKEN_OWNERSHIP_ERROR | Refresh token does not belong to current user |
| RefreshTokenNotFoundException | 404 REFRESH_TOKEN_NOT_FOUND | Refresh token not found |
| EntityNotFoundException | 400 BAD_REQUEST | exception.message (например User &lt;email&gt; not found) |
| FileItemNotFoundException | 404 FILE_ITEM_NOT_FOUND | File item &lt;id&gt; not found |
| FolderNotFoundException | 404 NOT_FOUND | Folder &lt;id&gt; not found |
| FileConflictException / FolderConflictException | 409 CONFLICT | service message: name/checksum/reserved conflict |
| FolderOperationException | 400 BAD_REQUEST | system/nonempty/cycle/name message |
| FileSizeLimitExceededException | 413 FILE_TOO_LARGE | File size exceeds limit: &lt;max&gt; bytes |
| MaxUploadSizeExceededException | 413 FILE_TOO_LARGE | Multipart request exceeds configured limit |
| IllegalArgumentException | 400 BAD_REQUEST | exception.message; empty file, invalid page, batch, path |
| DataIntegrityViolationException | 500 DATABASE_CONSTRAINT_VIOLATION | Database constraint violation |
| HttpMessageNotReadableException | 400 BAD_REQUEST | Malformed or missing request body |
| MissingServletRequestParameterException | 400 BAD_REQUEST | Required request parameter is missing |
| MethodArgumentNotValidException | 400 VALIDATION_ERROR | Validation failed + fieldErrors from field path |
| ConstraintViolationException | 400 VALIDATION_ERROR | Validation failed; последний segment propertyPath как key |
| JSON entry point | 401 UNAUTHORIZED | Unauthorized: access token expired or invalid |
| JSON denied handler | 403 FORBIDDEN | Forbidden |

`FileNotFoundException` и `TickerRequestException` имеют handlers 404 NOT_FOUND и400 BAD_REQUEST, но активный production-сценарий бросания не установлен. EntityAlreadyExistException и ErrorCode.INTERNAL_ERROR UNUSED в действующем error contract.

## Границы единого формата

PARTIAL: GlobalExceptionHandler имеет handlers конкретных типов, без catch-all Exception/IOException. Upload/copy/download IO, runtime mail/template errors, NoSuchElementException из access lookup и другие необработанные ошибки не получают гарантированный ErrorResponse. Ошибки вне MVC advice также проходят framework/security machinery. Семь STUB возвращают501 `{message}`, не ErrorResponse.

Missing ordinary query parameter обрабатывается custom400 BAD_REQUEST. Отсутствующая multipart file part — отдельный framework-сценарий, не обещанный тем же handler. Conversion, unsupported media type, method mismatch и no-route не имеют общего собственного mapping. OPEN-SRV-030 фиксирует точный wire-body/secondary statuses error dispatch; фиксированный Spring-default JSON не является гарантией этой спецификации.

## Валидация и границы доступа

Bean Validation задаёт обязательность, формат и длины DTO. Сервис дополнительно нормализует имена и проверяет folder ownership/инварианты. БД обеспечивает часть unique/FK/NOT NULL. Чужие file/folder ID скрываются как404; для refresh ownership используется403. Login unknown/wrong-password/disabled/banned объединён в401 INVALID_CREDENTIALS.

Empty upload→400, превышение service/servlet limit→413. Raw checksum batch>500→400 BAD_REQUEST, неверные элементы→400 VALIDATION_ERROR. Copy checksum/name и file name вне CAMERA→409. Move без checksum pre-check может дойти до unique constraint→500 DATABASE_CONSTRAINT_VIOLATION. Этот 500 может зависеть от неизменного конфликта данных, а не временной недоступности.

## Компенсация и скрытые отказы

Upload DataIntegrityViolation после move вызывает cleanup собственного final и reread matching FileItem; найденный duplicate возвращается200. Повторного INSERT нет. Folder root/system creation тоже пытается reread после saveAndFlush violation, но в той же transaction; успешное конкурентное восстановление не гарантировано. Rename/move folder catch может не охватить deferred commit error.

При частичном IO до успешного завершения move/copy очистка final/partial target не гарантирована (VER-SRV-004). Mapper/runtime failure после DB commit может привести к удалению bytes при сохранённых DB rows (VER-SRV-005); это условный риск control flow, не воспроизведённый сбой.

Extractor exception→WARN и пустая metadata, upload продолжается. Delete FS IOException после commit→ERROR и204. Cleanup IOException→ERROR, исходная ошибка остаётся. SMTP MessagingException ловится register/reset-request: при успешном логировании flow продолжает ответ об отправке; null cause приводит к NPE. Runtime MailException не покрывается этим catch. Поэтому успешный HTTP message не подтверждает доставку письма.

Автоматических retries, Retry-After, retryable flag, очереди восстановления, Idempotency-Key и сохранённого результата по request ID нет. Аварийный restart не запускает сверку DB/FS. Восстановление missing bytes повторным same-folder upload не выполняется.

## Свойства повторного вызова

| Операция | Эффект повтора при неизменном окружении | Ограничение |
| --- | --- | --- |
| Get/list/checksums/exists/children/download | Чтение | Нет snapshot между запросами; bytes отдельно от DB |
| GET root | Возвращает один ROOT, при отсутствии создаёт | Имеет write side effect; concurrency caveat |
| Upload тех же bytes в ту же folder | Возвращает существующий FileItem с200 | После move/delete ID может быть иным; missing bytes не чинит |
| Rename/move | Применяет то же целевое значение | Возможны concurrent changes/constraints |
| Copy | После успешной копии target checksum даёт409 | Нет кэша прежнего успешного ответа |
| Delete | Целевой эффект отсутствия записи сохраняется | Первый204, затем404; FS retry по ID отсутствует |
| Login | Создаёт новую refresh row | Не повторяет один и тот же persisted effect |
| Refresh | Refresh не потребляется, снова выдаётся access | Не продлевает срок и не обещает уникальную строку access |
| Logout | Отзыв сохраняется | Single revokedAt обновляется, нужен access |
| Register | Существующий email→409 | User мог сохраниться до SMTP failure |
| Reset request | Новый code и вызов SMTP | Неатомарно с доставкой |
| Confirm code | Повтор использованного code→400 | Это не idempotent success response |

Разный HTTP-ответ повторного DELETE не отменяет идемпотентность его целевого эффекта. Таблица описывает свойства сервера и не назначает клиенту политику retries, удаления локальных файлов или восстановления аккаунта.
