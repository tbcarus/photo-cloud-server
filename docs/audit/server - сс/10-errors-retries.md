# 10. Errors & Retries

---

## 1. Иерархия исключений

**[CONFIRMED]** 17 собственных классов исключений, **все наследуют напрямую `RuntimeException`** — общего базового класса приложения нет.

```
java.lang.RuntimeException
├── BadRegistrationRequest          (+ ErrorType errorType)
├── DuplicateEmailException         (без полей)
├── EntityAlreadyExistException     (entityName, message)          [UNUSED]
├── EntityNotFoundException         (entityName, message)
├── FileConflictException           (message)
├── FileItemNotFoundException       (сообщение из id)
├── FileNotFoundException           (FileName, message)            [UNUSED]
├── FileSizeLimitExceededException  (сообщение из maxSizeBytes)
├── FolderConflictException         (message)
├── FolderNotFoundException         (message | из id)
├── FolderOperationException        (message)
├── InvalidCredentialsException     (без полей)
├── InvalidRefreshTokenException    (без полей)
├── RefreshTokenNotFoundException   (без полей)
├── RefreshTokenOwnershipException  (без полей)
├── TickerRequestException          (message)                      [UNUSED, чужеродный]
└── TokenRevokedException           (token, message)
```

**[CONFIRMED][INCONSISTENCY]** Несогласованность конструкторов:

- Часть классов использует `super(message)` (`FileConflictException`, `FolderNotFoundException`, `FileItemNotFoundException`, `FileSizeLimitExceededException`, `FolderOperationException`, `FolderConflictException`) — `getMessage()` работает штатно.
- Часть использует Lombok `@RequiredArgsConstructor` + `@Getter` с **полем `message`**, не передавая его в `super` (`EntityNotFoundException`, `EntityAlreadyExistException`, `FileNotFoundException`, `TickerRequestException`, `TokenRevokedException`). У них `RuntimeException.getMessage()` вернул бы `null`, но Lombok-геттер `getMessage()` **перекрывает** метод суперкласса и возвращает поле. Работает, но хрупко: `super.getMessage()`, stacktrace и стандартные логгеры увидят `null`.
- Часть вообще без сообщения (`InvalidCredentialsException`, `DuplicateEmailException`, `InvalidRefreshTokenException`, `RefreshTokenNotFoundException`, `RefreshTokenOwnershipException`) — текст задаётся в `GlobalExceptionHandler`.

**[CONFIRMED]** `ErrorType` — вспомогательный enum с русскоязычными сообщениями (`PERIOD_EXPIRED`, `NOT_FOUND`, `DO_NOT_MATCH`, `WRONG_LENGTH`, `TOO_MUCH_REPEAT_REQUESTS`); фактически используются только `NOT_FOUND` и `TOO_MUCH_REPEAT_REQUESTS` (последний — в невызываемом методе).

**[CONFIRMED][UNUSED]** `TickerRequestException` не имеет отношения к домену (наследие другого проекта), но зарегистрирован в `GlobalExceptionHandler`.

---

## 2. Глобальный обработчик

**[CONFIRMED]** Единственный: `GlobalExceptionHandler` (`@RestControllerAdvice`), 23 `@ExceptionHandler`-метода. Локальных `@ExceptionHandler` в контроллерах нет.

| # | Exception | Status | ErrorCode | message |
| --- | --- | --- | --- | --- |
| 1 | `BadRegistrationRequest` | `400` | `BAD_REGISTRATION_REQUEST` | `errorType.getTitle()` (русский текст) |
| 2 | `TokenRevokedException` | `403` | `REFRESH_TOKEN_REVOKED` | `e.getMessage()` → `"token revoked"` |
| 3 | `InvalidCredentialsException` | `401` | `INVALID_CREDENTIALS` | `"Invalid email or password"` |
| 4 | `DuplicateEmailException` | `409` | `CONFLICT` | `"Email is already registered"` |
| 5 | `InvalidRefreshTokenException` | `401` | `INVALID_REFRESH_TOKEN` | `"Invalid refresh token"` |
| 6 | `RefreshTokenOwnershipException` | `403` | `REFRESH_TOKEN_OWNERSHIP_ERROR` | `"Refresh token does not belong to current user"` |
| 7 | `RefreshTokenNotFoundException` | `404` | `REFRESH_TOKEN_NOT_FOUND` | `"Refresh token not found"` |
| 8 | `EntityNotFoundException` | **`400`** | `BAD_REQUEST` | `e.getMessage()` |
| 9 | `FileItemNotFoundException` | `404` | `FILE_ITEM_NOT_FOUND` | `"File item {id} not found"` |
| 10 | `FileNotFoundException` | `404` | `NOT_FOUND` | `e.getMessage()` |
| 11 | `FileSizeLimitExceededException` | `413` | `FILE_TOO_LARGE` | `"File size exceeds limit: N bytes"` |
| 12 | `FileConflictException` | `409` | `CONFLICT` | `e.getMessage()` |
| 13 | `FolderNotFoundException` | `404` | `NOT_FOUND` | `e.getMessage()` |
| 14 | `FolderConflictException` | `409` | `CONFLICT` | `e.getMessage()` |
| 15 | `FolderOperationException` | `400` | `BAD_REQUEST` | `e.getMessage()` |
| 16 | `MaxUploadSizeExceededException` | `413` | `FILE_TOO_LARGE` | `"Multipart request exceeds configured limit"` |
| 17 | `IllegalArgumentException` | `400` | `BAD_REQUEST` | `e.getMessage()` |
| 18 | `DataIntegrityViolationException` | **`500`** | `DATABASE_CONSTRAINT_VIOLATION` | `"Database constraint violation"` |
| 19 | `TickerRequestException` | `400` | `BAD_REQUEST` | `e.getMessage()` |
| 20 | `HttpMessageNotReadableException` | `400` | `BAD_REQUEST` | `"Malformed or missing request body"` |
| 21 | `MissingServletRequestParameterException` | `400` | `BAD_REQUEST` | `"Required request parameter is missing"` |
| 22 | `MethodArgumentNotValidException` | `400` | `VALIDATION_ERROR` | `"Validation failed"` + `fieldErrors` |
| 23 | `ConstraintViolationException` | `400` | `VALIDATION_ERROR` | `"Validation failed"` + `fieldErrors` |

**[CONFIRMED][RISK] Критические пробелы обработчика:**

1. **Нет `@ExceptionHandler(Exception.class)`** — любое необработанное исключение (`NullPointerException`, `NoSuchElementException`, `IOException`, `SQLException`) уходит в стандартный Spring Boot `BasicErrorController` и возвращает **не `ErrorResponse`**, а `{"timestamp":..., "status":500, "error":"Internal Server Error", "path":"..."}`. Клиент получает **два разных формата ошибок** в зависимости от того, обработано исключение или нет.
2. `ErrorCode.INTERNAL_ERROR` объявлен, но **[UNUSED]** — ни один handler его не использует.
3. `DataIntegrityViolationException → 500` — единственный обработчик, отдающий 5xx. Для нарушения `uk_file_item_user_folder_checksum` при `move` это семантически неверно: это конфликт данных (`409`), а не отказ сервера.
4. **Ни один handler не логирует исключение.** `500` уходит клиенту без записи stacktrace в лог — диагностика по `ErrorResponse.id` невозможна, потому что этот id нигде не сохраняется.
5. `EntityNotFoundException → 400` вместо `404` — используется в `forgotPassword()` для несуществующего email (это и создаёт user enumeration).
6. `IllegalArgumentException → 400` перехватывает и доменные ошибки («Файл пустой», «File name must not be blank»), и `StoragePathResolver`'s «Invalid storage path» (внутренняя ошибка безопасности), и превышение batch-лимита.

---

## 3. Error DTO

**[CONFIRMED]** `exception.dto.ErrorResponse` (Lombok `@Data @Builder`):

```json
{
  "id": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": { "email": "Email must be valid" }
}
```

| Поле | Тип | Семантика |
| --- | --- | --- |
| `id` | `UUID` | генерируется на каждую ошибку, **нигде не логируется и не сохраняется** |
| `code` | `ErrorCode` (enum, сериализуется строкой) | машиночитаемый код |
| `message` | `String` | человекочитаемое описание (частично русский, частично английский) |
| `fieldErrors` | `Map<String,String>` | только для validation; иначе присутствует со значением `null` |

**`ErrorCode` [CONFIRMED]** — 16 значений: `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `BAD_REQUEST`, `NOT_FOUND`, `CONFLICT`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_REVOKED`, `REFRESH_TOKEN_NOT_FOUND`, `REFRESH_TOKEN_OWNERSHIP_ERROR`, `FILE_ITEM_NOT_FOUND`, `FILE_TOO_LARGE`, `BAD_REGISTRATION_REQUEST`, `DATABASE_CONSTRAINT_VIOLATION`, `INTERNAL_ERROR` (последний не используется).

**[CONFIRMED][INCONSISTENCY]** Формат `ErrorResponse` **не применяется** к:
- заглушкам `501` (7 endpoint'ов) — они отдают `{"message": "..."}`;
- успешным ответам auth/register/logout — тот же `Map<String,String>`;
- необработанным `500` — Spring-дефолт;
- `GET /auth/register/confirm` при успехе — `text/plain`.

---

## 4. Категории ошибок

### 4.1. Validation errors

| Аспект | Значение |
| --- | --- |
| Источник | Bean Validation в DTO (`@NotBlank`, `@Email`, `@Size`, `@NotNull`, `@NotEmpty`, `@Pattern`) |
| Тип | `MethodArgumentNotValidException` (тело), `ConstraintViolationException` (`@RequestParam` при `@Validated` на классе) |
| Status | `400` |
| Body | `code: VALIDATION_ERROR`, `fieldErrors: {поле: сообщение}` |
| Retryable | **Нет** — повтор без изменения данных даст тот же результат |

**[CONFIRMED]** Имя поля в `fieldErrors`: для тела — имя свойства (`email`), для элементов коллекции — с индексом (`checksums[0]`), для `@RequestParam` — последний сегмент property path (`ConstraintViolationException` handler делает `path.substring(path.lastIndexOf('.') + 1)`).

### 4.2. Auth errors

| Ситуация | Exception | Status | Code | Retryable |
| --- | --- | --- | --- | --- |
| Нет/невалидный/истёкший access token | — (entry point) | `401` | `UNAUTHORIZED` | **условно** — после успешного refresh |
| Неверные credentials | `InvalidCredentialsException` | `401` | `INVALID_CREDENTIALS` | нет |
| Disabled/banned пользователь | `InvalidCredentialsException` | `401` | `INVALID_CREDENTIALS` | нет |
| Refresh не найден (refresh) | `InvalidRefreshTokenException` | `401` | `INVALID_REFRESH_TOKEN` | **нет** — требуется повторный login |
| Refresh истёк/неверный тип | `InvalidRefreshTokenException` | `401` | `INVALID_REFRESH_TOKEN` | нет |
| Refresh отозван | `TokenRevokedException` | `403` | `REFRESH_TOKEN_REVOKED` | **нет** — терминально |
| Refresh чужой | `RefreshTokenOwnershipException` | `403` | `REFRESH_TOKEN_OWNERSHIP_ERROR` | нет |
| Refresh не найден (logout) | `RefreshTokenNotFoundException` | `404` | `REFRESH_TOKEN_NOT_FOUND` | нет |
| Access token валиден, пользователь удалён | `NoSuchElementException` | **`500`** | — (Spring-дефолт) | нет |

### 4.3. Upload errors

| Ситуация | Exception | Status | Code | Retryable |
| --- | --- | --- | --- | --- |
| Пустой файл | `IllegalArgumentException` | `400` | `BAD_REQUEST` | нет |
| Отсутствует часть `file` | `MissingServletRequestParameterException` | `400` | `BAD_REQUEST` | нет |
| Превышен `storage.max-file-size-bytes` | `FileSizeLimitExceededException` | `413` | `FILE_TOO_LARGE` | нет |
| Превышен multipart-лимит | `MaxUploadSizeExceededException` | `413` | `FILE_TOO_LARGE` | нет |
| Конфликт имени (не CAMERA) | `FileConflictException` | `409` | `CONFLICT` | **нет** без переименования |
| Чужой/несуществующий `folderId` | `FolderNotFoundException` | `404` | `NOT_FOUND` | нет |
| Дубль по checksum в папке | — | `200` | — | **не ошибка** (идемпотентный ответ) |
| Обрыв сети во время передачи | `IOException` | `500` Spring-дефолт (или соединение просто закрыто) | — | **ДА** — сервер не оставил следов |
| Сбой ФС при move | `IOException` | `500` Spring-дефолт | — | да |
| Гонка по checksum | `DataIntegrityViolationException` (перехвачена внутри) | `200` | — | не ошибка |

### 4.4. Storage errors

| Ситуация | Обработка | Status | Retryable |
| --- | --- | --- | --- |
| Файл отсутствует при download | `FileItemNotFoundException` | `404` | **нет** — данные утеряны безвозвратно |
| Путь вне `storage.root` | `IllegalArgumentException("Invalid storage path")` | `400` | нет (не должно случаться) |
| Нет места на диске | `IOException` | `500` Spring-дефолт | да, после освобождения места |
| Ошибка удаления файла | **проглатывается**, `log.error` | `204` | клиент не узнаёт |
| `Files.copy` — целевой файл существует | `FileAlreadyExistsException` | `500` Spring-дефолт | нет |

### 4.5. Database errors

| Ситуация | Обработка | Status | Retryable |
| --- | --- | --- | --- |
| Нарушение `uk_file_item_user_folder_checksum` при upload | перехвачено, вернётся существующий | `200` | не ошибка |
| То же при `move` | **не перехвачено** | **`500 DATABASE_CONSTRAINT_VIOLATION`** | **нет** без смены папки |
| Нарушение `uk_folder_user_parent_name` | `FolderService` ловит и превращает в `FolderConflictException` | `409` | нет |
| Гонка при создании ROOT/CAMERA/FILES | перехвачено, перечитывает | — | не ошибка |
| Недоступна БД | `DataAccessResourceFailureException` | `500` Spring-дефолт | **да** — временная |
| Таймаут соединения | то же | `500` Spring-дефолт | да |

### 4.6. Email errors

**[CONFIRMED]** `MessagingException` при регистрации и запросе сброса пароля **поглощается** и логируется — клиент получает `201`/`200` независимо от того, ушло письмо или нет.
**[RISK]** `log.error("Nothing was sent {}", e.getCause().getMessage())` → NPE при `cause == null`.

---

## 5. Матрица retryability для клиента

**[CONFIRMED]** — выведено из фактического поведения сервера:

| Status | Code | Причина | Безопасен ли retry | Что делать клиенту |
| --- | --- | --- | --- | --- |
| `400` | `VALIDATION_ERROR` | неверные данные | ❌ бессмысленно | исправить запрос |
| `400` | `BAD_REQUEST` | доменное ограничение | ❌ | исправить запрос |
| `400` | `BAD_REGISTRATION_REQUEST` | код неверен/использован/истёк | ❌ | запросить новый код (**resend не реализован**) |
| `401` | `UNAUTHORIZED` | access token истёк/невалиден | ✅ **после refresh** | refresh → повторить запрос |
| `401` | `INVALID_CREDENTIALS` | логин/пароль/заблокирован | ❌ | показать ошибку входа |
| `401` | `INVALID_REFRESH_TOKEN` | refresh недействителен | ❌ | **полный re-login** |
| `403` | `REFRESH_TOKEN_REVOKED` | выполнен logout | ❌ | **полный re-login** |
| `403` | `REFRESH_TOKEN_OWNERSHIP_ERROR` | чужой токен | ❌ | очистить хранилище токенов, re-login |
| `403` | `FORBIDDEN` | AccessDenied (практически недостижим) | ❌ | — |
| `404` | `FILE_ITEM_NOT_FOUND` | нет/чужой/файл утерян | ❌ | удалить из локального индекса |
| `404` | `NOT_FOUND` | папка не найдена | ❌ | пересинхронизировать дерево папок |
| `404` | `REFRESH_TOKEN_NOT_FOUND` | токена нет в БД | ❌ | считать logout выполненным |
| `409` | `CONFLICT` (имя) | имя занято | ⚠️ только с другим именем | переименовать и повторить |
| `409` | `CONFLICT` (checksum, copy) | такой файл уже в папке | ❌ | не копировать |
| `413` | `FILE_TOO_LARGE` | превышен лимит | ❌ | не отправлять этот файл |
| `500` | `DATABASE_CONSTRAINT_VIOLATION` | конфликт данных (в т.ч. move) | ❌ | **не повторять** — это конфликт, а не сбой |
| `500` | Spring-дефолт (без `code`) | необработанное исключение | ⚠️ **возможно** | retry с exponential backoff — может быть временным (БД, диск, сеть) |
| `501` | — (`{"message":...}`) | заглушка | ❌ | функция не реализована |
| нет ответа / таймаут | — | сеть | ✅ **для upload безопасно** | см. §6 |

**[CONFIRMED][RISK]** `500` не различает временный сбой (БД недоступна) и постоянный конфликт (`DATABASE_CONSTRAINT_VIOLATION`) — но у второго есть `code`, а у первого его нет вообще. Это единственный доступный клиенту признак, и он неявный.

---

## 6. Идемпотентность

**[CONFIRMED]** Идемпотентность **не построена на ключах** (`Idempotency-Key`, `If-Match`, ETag отсутствуют), но частично достигается за счёт семантики операций:

| Операция | Идемпотентна? | Механизм |
| --- | --- | --- |
| `POST /files` (upload) | ✅ **да**, в пределах папки | дубль по `user+folder+checksum` возвращает существующий `FileItem` с `200` |
| `POST /files/checksums/exists` | ✅ да | read-only |
| `GET /*` | ✅ да | кроме `GET /folders/root`, который может создать ROOT (создание идемпотентно) |
| `POST /auth/refresh-token` | ✅ да | записей в БД нет, можно повторять |
| `POST /auth/logout` | ⚠️ частично | повтор с уже отозванным токеном вернёт `200` (проверки `revoked` в logout нет) — фактически идемпотентно |
| `POST /auth/logout-all` | ✅ да | |
| `DELETE /files/{id}` | ❌ **нет** | повтор даст `404` вместо `204` |
| `DELETE /folders/{id}` | ❌ нет | то же |
| `PATCH /files/{id}` (rename) | ✅ да | повтор с тем же именем: `ensureFileNameAvailable` исключает сам файл по `IdNot` |
| `POST /files/{id}/move` | ✅ да | перемещение в ту же папку пройдёт повторно |
| `POST /files/{id}/copy` | ❌ **нет** | повтор даст `409` (checksum уже в папке) — но дубля не создаст |
| `POST /auth/login` | ❌ нет | каждый вызов создаёт новую строку `refresh_token` |
| `POST /auth/register` | ❌ нет | повтор даст `409` |
| `GET /auth/register/confirm` | ❌ нет | повтор даст `400` (код уже `used`) |
| `POST /auth/password/reset/confirm` | ❌ нет | то же |

**[CONFIRMED]** Ключевое следствие для клиента: **upload безопасно повторять** после обрыва сети или таймаута. Худший случай — файл уже загрузился, и повтор вернёт существующую запись. Дубликат не создастся благодаря `uk_file_item_user_folder_checksum`.

**[CONFIRMED][RISK]** Исключение: повтор upload **в другую папку** дубль создаст (это by design), поэтому клиент должен повторять запрос **с тем же `folderId`**.

---

## 7. Retry-механизмы на сервере

**[CONFIRMED]** **Отсутствуют полностью:**

| Механизм | Есть? |
| --- | --- |
| `@Retryable` / Spring Retry | нет (зависимости нет) |
| Ретраи транзакций при deadlock/serialization failure | нет |
| Ретраи отправки писем | нет — одна попытка, ошибка логируется |
| Ретраи удаления файла с диска | нет — одна попытка, ошибка логируется |
| Dead letter queue | нет |
| Circuit breaker | нет |
| Таймауты на внешние вызовы (SMTP) | не настроены явно — используются значения JavaMail по умолчанию |

**[CONFIRMED]** Единственная «повторная попытка» в коде — обработка гонки в `FolderService.createRootRaceSafe()` / `createSystemChildRaceSafe()` и в `FileItemService.uploadFile()`: перехват `DataIntegrityViolationException` с последующим повторным чтением. Это не retry, а разрешение конкурентного конфликта.

---

## 8. Ошибки, не превращающиеся в HTTP-ответ

**[CONFIRMED]** Три места, где ошибка «проглатывается»:

| Место | Код | Последствие |
| --- | --- | --- |
| `UserService.register()` / `forgotPassword()` | `catch (MessagingException e) { log.error(...) }` | письмо не ушло, клиент получил успех |
| `FileItemService.deleteFileForCurrentUser()` | `catch (IOException ex) { log.error("Не удалось удалить физический файл...") }` | orphan-файл, клиент получил `204` |
| `FileItemService.deleteIfExists()` | `catch (IOException cleanupEx) { log.error("Не удалось удалить файл при cleanup") }` | orphan/temp-файл при откате |
| `DrewFileMetadataExtractor.extract()` | `catch (Exception ex) { log.warn(...); return пустой }` | **намеренно** — сбой EXIF не должен ломать upload (тест `metadataExtractionFailureDoesNotFailUpload`) |

---

## 9. Локализация сообщений

**[CONFIRMED][INCONSISTENCY]** Смешаны языки:

| Язык | Примеры |
| --- | --- |
| Английский | `"Invalid email or password"`, `"File with this name already exists in folder"`, `"Cannot delete system folder"`, `"Unauthorized: access token expired or invalid"` |
| Русский | `"Файл пустой"` (`IllegalArgumentException` при пустом upload), все значения `ErrorType` (`"Запись не найдена"`, `"Вышел срок действия запроса..."`) |

Механизма i18n (`MessageSource`, `messages.properties`) нет — клиент не может выбирать язык, а сообщения не предназначены для прямого показа пользователю без перевода на стороне клиента.
