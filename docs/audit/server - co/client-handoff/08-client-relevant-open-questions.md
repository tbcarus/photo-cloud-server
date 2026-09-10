# 08. Открытые вопросы для Android-аудита

Все вопросы [INFERRED]: необходимость уточнения следует из текущего server-контракта; ответы и требования не придуманы. Сначала проверить фактическое Android-поведение, затем сверять с этими границами.

| Вопрос | Что точно делает сервер | Что предстоит установить в клиенте/требованиях |
| --- | --- | --- |
| Как получить cameraId до первого pre-check? | GET root/children не создают CAMERA; exists требует folderId, default upload создаёт | Есть ли bootstrap без pre-check первого файла и как кешируется folderId |
| Что считать remote identity? | FileItem.id адресует операции; checksum уникален только user+folder | Хранится ли mapping local asset↔server id+folder+hash и когда обновляется |
| Как трактовать duplicate upload со старым именем? |200 old DTO, новое filename не применяется | Не предполагает ли клиент новый ID/имя; какой source of truth нужен UI |
| Что делать с409 copy/name? | Duplicate upload200, copy duplicate409, name conflict409 | Не выполняется ли blind retry409; как различаются name/checksum conflicts |
| Что делать с500 move checksum? | DATABASE_CONSTRAINT_VIOLATION, не guaranteed transient | Не попадает ли deterministic конфликт в бесконечную retry queue |
| Как обрабатывать missing bytes при existing? | exists existing, metadata200, download404, upload old DTO200 | Есть ли сигнал пользователю/серверу; серверный repair contract отсутствует |
| Что после потери upload response? | Commit мог завершиться; same bytes/folder repeat200 existing | Хранится ли неопределённый исход и сверяется ли тот же target/hash |
| Что если local file изменился после scan? | Сервер считает hash реально переданных bytes | Пересчитывает ли клиент hash и сверяет ли response.checksum |
| Что если local file удалён после scan? | Сервер ничего не знает, загрузить bytes без источника нельзя | Как завершается local queue entry |
| Должно ли удаление на телефоне удалять серверную копию? | Нет auto propagation/tombstones | Какое требование retention/backup предполагается |
| Должно ли server delete/move вызывать повторный auto-upload? | Hash может стать missing в CAMERA, separate deleted state нет | Есть ли local suppression/история ручного действия |
| Как синхронизируются несколько устройств? | Несколько refresh/account, deviceId не хранится | Используют ли устройства общий folder и как разрешают concurrent changes |
| Что локально очищается при logout/account switch? | Revoke refresh, access ещё жив; queue/files server не удаляет | Token/account scoping локальных БД, jobs, cached folder IDs |
| Что делать при expired access на logout? | Protected logout401; пригодный refresh может дать access | Как клиент подтверждает удалённый revoke и не считает local clear успешным revoke |
| Требует ли продукт немедленного logout/reset/ban? | Access не revoked, reset tokens не трогает, ban checked only login | Совпадает ли UI и security expectation с сервером |
| Не отправляет ли interceptor expired Bearer на refresh? | Bad Bearer блокирует public refresh401 | Есть ли исключения для auth/public routes и loop guard |
| Как обновляется refresh storage? | Refresh response содержит только accessToken | Не перезаписывает ли клиент refresh null/пустым, ожидая rotation |
| Как пользователь завершает reset из письма? | Browser page501, JSON confirm работает | Есть ли deep link/manual code UI и какой маршрут используется |
| Как интерпретировать capturedAt? | Local datetime без offset, video fallback upload time | Не читает ли клиент его как UTC, не ожидает ли client creation timestamp |
| Какие media metadata обязательны Android UI? | Все metadata fields nullable; duration не извлекается | Не возникают ли ошибки при null/unknown FileType/metadata absent |
| Как обрабатываются unknown/non-JSON ошибки? | Catch-all ErrorResponse отсутствует;501 message-only | Умеет ли parser сохранить HTTP status при другом body |
| Какие ограничения размера/пакета локально известны? |100MiB service,110MB multipart request,500 raw hashes | Разбиваются ли batches до отправки; какое поведение413 |
| Какие aliases/фильтры использует Android? | /files и /files/upload оба работают; type/from/to не объявлены | Не полагается ли клиент на несуществующие filter/search/resume API |
| Как обрабатывается copy без folder? | Default source folder→checksum409 даже с новым name | Не показывает ли UI недостижимую операцию «копия здесь» |
| Какова семантика server rename/copy Unicode имени? | Sanitizer заменяет chars, response authoritative; attachment без filename* | Сверяется ли итоговое имя, есть ли проблемы download filename |
| Должны ли Camera/Files ограничивать FileType? | Explicit target допускает любую свою folder | Какие предположения о типах делает Android при показе folder |
| Должен ли клиент посылать client metadata/status? | Таких upload fields нет | Не считает ли он ignored fields подтверждёнными сервером |

Этот пакет не задаёт Android-архитектуру или новый алгоритм синхронизации. Источники server facts — файлы01–07 handoff и [server controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller).
