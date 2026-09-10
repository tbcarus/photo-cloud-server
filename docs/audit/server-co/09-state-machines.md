# Состояния и переходы

[CONFIRMED] Формального workflow engine, UploadStatus/MediaStatus, status column или upload session entity не найдено. Названия состояний upload ниже — [INFERRED] аналитические имена для этапов FileItemService, они не существуют в JSON/БД.

## Upload: неустойчивая state machine

| Состояние | Вход/переход и инициатор | Ошибка / конечное состояние |
| --- | --- | --- |
| RECEIVED | HTTP multipart; auth и binding до service | Unauthorized/binding/empty завершают запрос |
| TEMP_WRITING | Service createTempFile + stream/hash | IOException/limit → cleanup, ошибка |
| TEMP_READY | Stream завершён; MIME/metadata/folder/name | Metadata exception → fallback; прочие → cleanup |
| DUPLICATE | Найден user/folder/checksum | Temp cleanup, 200 existing; terminal запроса |
| FINAL_MOVED | Move temp → final | DB transaction ещё не гарантирована |
| DB_COMMITTED | TransactionTemplate вернул saved row | Mapper/response всё ещё могут не дойти до клиента |
| ACKNOWLEDGED | Ответ 200 получен клиентом | Сервер отдельный флаг подтверждения не сохраняет |
| FAILED | Exception обработан | Cleanup best effort; row/final возможны при границах commit |
| CRASHED | Процесс исчез в любой стадии | Автовосстановление отсутствует |

~~~mermaid
stateDiagram-v2
    [*] --> RECEIVED
    RECEIVED --> TEMP_WRITING
    TEMP_WRITING --> TEMP_READY
    TEMP_READY --> DUPLICATE: tuple exists
    TEMP_READY --> FINAL_MOVED: new tuple
    FINAL_MOVED --> DB_COMMITTED: commit
    DB_COMMITTED --> ACKNOWLEDGED: client receives response
    TEMP_WRITING --> FAILED: stream/limit
    TEMP_READY --> FAILED: validation/storage
    FINAL_MOVED --> FAILED: DB failure
    DUPLICATE --> [*]
    ACKNOWLEDGED --> [*]
    FAILED --> [*]
~~~

[CONFIRMED] Ack не является server persisted state, endpoint markUploaded/complete не существует. После network timeout неизвестно, дошёл ли commit. Stable tuple repeat возвращает existing. Crash-state не обнаруживается scanner-ом и не имеет recovery transition.

## FileItem/StoredObject: состояние по наличию записей и байтов

| Комбинация | Наблюдаемость / переход |
| --- | --- |
| Нет FileItem/SO/bytes | До upload или после полного удаления |
| FileItem+SO+bytes | Нормальная сохранённая запись; rename/move меняют логические поля |
| FileItem+SO без readable bytes | List/card/exists видят запись; download404; upload duplicate её не лечит |
| Bytes без DB refs | Orphan после crash/delete failure; API не видит |
| SO без FileItem | FK допускает; может остаться от внешних действий/сбоев неатомарных операций вне текущего pipeline |
| Несколько FileItem на SO | Разрешено моделью; current upload/copy не создают sharing; owner delete убирает все |
| deletedAt != null | Поле возможно в данных, но текущие сервисы не назначают/не фильтруют его |

[CONFIRMED] Lifecycle source: FileItemService.java, FileItem.java, StoredObject.java. Terminal API delete — hard deletion, восстановления/корзины нет. FileType и FolderType не processing states и в существующих операциях не меняются.

## Пользователь

[CONFIRMED] Начальные enabled=false,banned=false. Confirm ACTIVATE code → enabled=true. Banned column учитывается login, но setter endpoint отсутствует. Password reset меняет hash и lastUpdate; lastLoginAt меняется только при успешном login. Эти флаги независимы: enabled/banned могут образовывать четыре комбинации в БД.

~~~mermaid
stateDiagram-v2
    [*] --> RegisteredDisabled: register
    RegisteredDisabled --> Enabled: valid ACTIVATE code
    Enabled --> Enabled: login / password reset
~~~

[CONFIRMED] Ветка ban/unban отсутствует среди endpoints; внешний администратор не моделируется как реализованный процесс. После изменения флага уже существующий access/refresh не блокируется проверкой флага. User delete state transition API нет.

## EmailRequest

[CONFIRMED] Сохранённое состояние — type/used/createdAt; active/expired вычисляются на now. Начало used=false. ACTIVATE confirm → использованный код + enabled user. PASSWORD_RESET confirm → использованный код и другие reset-коды окна 3 дня; hash обновлён. По времени now >= createdAt+3 days → expired без DB UPDATE. Ошибка wrong-type/missing/used/expired → 400 BAD_REGISTRATION_REQUEST.

[CONFIRMED] Терминальны для использования used=true или expiry. Сам isActive() не учитывает used; сохранённые строки автоматически не удаляются. Нет sent/delivery_failed/email-confirmation-pending state: успешность SMTP в коде не хранится. checkAndGenerateCode/delete не образуют действующий cleanup workflow.

## Refresh/session

[CONFIRMED] Состояние пригодности — row exists + !revoked + valid signed REFRESH + future exp + matching user. Login создаёт активную row. Refresh выдаёт access без изменения row. Logout переводит revoked, logout-all/others меняют только flag. По expiry строка остаётся, JWT parser отвергает. Revoke terminal для данной row: un-revoke API нет, но новый login создаёт другую row. Ошибка revoked=403, unknown/expired/invalid=401; logout unknown=404, foreign=403.

~~~mermaid
stateDiagram-v2
    [*] --> Active: login
    Active --> Active: refresh issues access
    Active --> Revoked: logout variants
    Active --> Expired: exp reached
    Revoked --> [*]
    Expired --> [*]
~~~

[CONFIRMED] Diagram terminal означает невозможность refresh, не физическое удаление row. HTTP sessions отсутствуют. Access state — valid/expired по JWT; logout transition у него нет. Нет refresh rotation/token-family state.

## Folder lifecycle

[CONFIRMED] ROOT, Camera, Files lazy create → immutable type/name/parent для API. USER create → rename/move → delete только empty. Состояние empty вычисляется двумя exists queries; stored flag нет. Tree checks до move, без @Version/lock; competing moves могут нарушить глобальный invariant при проходящих локальных проверках — [RISK], не гарантированный исход каждого запроса.

