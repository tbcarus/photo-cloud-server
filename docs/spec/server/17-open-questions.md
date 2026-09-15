# Open questions

30 вопросов сгруппированы по решению или пределу доказательности. OPEN-SRV-001–024 — продуктовые/архитектурные решения;025–028 — неизвестные параметры deployment;029–030 — неустановленные детали framework wire-контракта. Они не превращают известный As-Is в неизвестность и не задают будущие требования. Ответы автоматически не назначаются.

## OPEN-SRV-001 — Удаления и направление синхронизации

**Type:** PRODUCT_DECISION

### Current As-Is

Нет delta/tombstone/device state; сервер принимает отдельные CRUD.

### Question

Как согласуются local deletion, remote deletion и remote move между клиентами?

### Why it matters

Направление sync и правила повторной загрузки.

Источник вопроса: A17 B1/C3/C4; B17 Q11/Q12.

## OPEN-SRV-002 — Несколько устройств и сессии

**Type:** PRODUCT_DECISION

### Current As-Is

Несколько refresh rows разрешены; device ID/last-used/session list нет.

### Question

Какова продуктовая модель нескольких устройств, лимита сессий и их идентификации?

### Why it matters

Auth UX, sync ownership и связь локального состояния.

Источник вопроса: A17 C1/E2/E3; B17 Q20.

## OPEN-SRV-003 — Локальная identity

**Type:** ARCHITECTURE_DECISION

### Current As-Is

Сервер знает FileItem.id/folder/checksum, но не путь и asset ID устройства. Фактическая Android-side identity будет установлена в PhotoCloud Android Specification; текущий OPEN относится к целевой системной модели связи local asset ↔ server identity.

### Question

Какова целевая системная модель связи local asset ↔ server identity, в том числе одного checksum в нескольких папках?

### Why it matters

Сопоставление Android и server data model.

Источник вопроса: A17 C2; B17 Q13/Q15.

## OPEN-SRV-004 — Первый CAMERA pre-check

**Type:** ARCHITECTURE_DECISION

### Current As-Is

`GET /folders/root` создаёт/возвращает только ROOT; exists требует folderId. CAMERA до первого default upload IMAGE/VIDEO может отсутствовать; такой upload создаёт CAMERA. После создания её ID доступен через direct children ROOT: `GET /folders/{rootId}/children`.

### Question

Должен ли существовать серверный bootstrap-механизм получения/создания CAMERA до первого upload, либо клиент должен использовать текущий lifecycle?

### Why it matters

Bootstrap автозагрузки.

Источник вопроса: B17 Q10.

## OPEN-SRV-005 — Неизвестный результат и missing bytes

**Type:** PRODUCT_DECISION

### Current As-Is

Нет upload session/ACK; repeated upload возвращает logical duplicate без repair bytes.

### Question

Как продукт трактует потерянный ответ и existing при недоступных bytes?

### Why it matters

Состояния клиента и восстановление целостности.

Источник вопроса: B17 Q14/Q18.

## OPEN-SRV-006 — Дубликаты, link и copy

**Type:** PRODUCT_DECISION

### Current As-Is

Дедупликация user/folder/checksum; cross-folder upload/copy создают bytes; copy без target конфликтует в source.

### Question

Какой смысл продукта закрепляется за duplicate, independent copy и ссылкой на existing object?

### Why it matters

Identity, расход места и contract копирования.

Источник вопроса: A17 A1–A3; B17 Q30/Q32.

## OPEN-SRV-007 — Sharing и owner delete

**Type:** PRODUCT_DECISION

### Current As-Is

API sharing нет; модель допускает references; owner-delete удаляет все.

### Question

Будут ли общие references частью продукта и каковы полномочия владельца при удалении?

### Why it matters

Ownership и lifecycle в System Specification.

Источник вопроса: A17 B3/H2; B17 Q26/Q27.

## OPEN-SRV-008 — Trash и retention

**Type:** PRODUCT_DECISION

### Current As-Is

Hard delete, deletedAt не используется; retention отсутствует.

### Question

Как трактуется удаление и требуется ли период восстановления/сохранения?

### Why it matters

Deletion lifecycle и хранение истории.

Источник вопроса: A17 B2; B17 Q28.

## OPEN-SRV-009 — Удаление аккаунта

**Type:** PRODUCT_DECISION

### Current As-Is

Нет user-delete API; SQL cascade не очищает FS/refresh.

### Question

Каково ожидаемое завершение lifecycle аккаунта и связанных данных?

### Why it matters

User, auth, physical storage lifecycle.

Источник вопроса: A17 B4; B17 Q25.

## OPEN-SRV-010 — Удаление дерева

**Type:** PRODUCT_DECISION

### Current As-Is

API удаляет только пустую USER-папку.

### Question

Какой смысл имеет удаление непустой папки в продукте?

### Why it matters

Folder/file ownership и destructive operations.

Источник вопроса: A17 B5.

## OPEN-SRV-011 — Типы файлов и системные папки

**Type:** PRODUCT_DECISION

### Current As-Is

Любой MIME принимается; тип выбирает только default folder; explicit target может быть любой свой.

### Question

Какие типы и правила помещения в CAMERA/FILES определяют продукт?

### Why it matters

Граница photo cloud / general storage и клиентский UI.

Источник вопроса: A17 D1/H1; B17 Q29.

## OPEN-SRV-012 — Конфликты имён

**Type:** PRODUCT_DECISION

### Current As-Is

Sanitize, case-insensitive precheck вне CAMERA, без auto-rename/overwrite/Unicode normalization.

### Question

Как трактуются совпадения имён, Unicode/case и конфликт при повторе с новым именем?

### Why it matters

File/folder naming contract.

Источник вопроса: A17 D3/D4; B17 Q15/Q33.

## OPEN-SRV-013 — Съёмка и media metadata

**Type:** PRODUCT_DECISION

### Current As-Is

LocalDateTime без offset; video/без EXIF → uploadedAt; client capturedAt не принимается.

### Question

Как интерпретируется время съёмки и источник metadata для видео/файла без EXIF?

### Why it matters

Сортировка, timeline и Android timestamp semantics.

Источник вопроса: A17 D5; B17 Q17/Q31.

## OPEN-SRV-014 — Представление списка и изменений

**Type:** PRODUCT_DECISION

### Current As-Is

Fixed sort, page pagination; full tree/delta feed нет.

### Question

Какой пользовательский смысл имеют порядок файлов, согласованность страниц и получение изменений?

### Why it matters

Listing и sync contract клиента.

Источник вопроса: A17 C4/C5.

## OPEN-SRV-015 — Media presentation

**Type:** PRODUCT_DECISION

### Current As-Is

Нет thumbnails, albums, tags/search entities.

### Question

Какие из этих представлений входят в продукт и как соотносятся с Folder?

### Why it matters

Границы capabilities, без назначения реализации сейчас.

Источник вопроса: A17 D2/H4.

## OPEN-SRV-016 — Политика токенов

**Type:** PRODUCT_DECISION

### Current As-Is

Access20min, refresh7days; logout/reset/ban не дают немедленного отзыва access.

### Question

Какие сроки и момент прекращения доступа являются продуктовыми ожиданиями?

### Why it matters

Security/session semantics.

Источник вопроса: A17 E1/E4/E5; B17 Q19.

## OPEN-SRV-017 — Пароль и ограничения запросов

**Type:** PRODUCT_DECISION

### Current As-Is

Password4..20; rate limiting не подключён.

### Question

Какая политика паролей и лимитов обращений закрепляется продуктом?

### Why it matters

Validation и security contract.

Источник вопроса: A17 E6; B17 Q23.

## OPEN-SRV-018 — Email и восстановление

**Type:** PRODUCT_DECISION

### Current As-Is

Code3days; resend501; reset page501; нет delivery tracking.

### Question

Как пользователь завершает recovery и что означает успех register/reset request без доставки?

### Why it matters

Email lifecycle, UX и восстановление аккаунта.

Источник вопроса: B17 Q21/Q22/Q23.

## OPEN-SRV-019 — Profile/settings

**Type:** PRODUCT_DECISION

### Current As-Is

GET profile реализован; PATCH/settings501; edit request model нет.

### Question

Каковы правила изменения displayName/email/settings?

### Why it matters

Account DTO и пользовательские действия.

Источник вопроса: B17 Q24.

## OPEN-SRV-020 — Клиенты и маршруты

**Type:** PRODUCT_DECISION

### Current As-Is

Два upload aliases работают; фактические Android routes/client behaviour должны быть установлены в PhotoCloud Android Specification; browser UI отсутствует. Поддерживаемые client versions/browser scenarios — отдельное продуктовое решение этого OPEN.

### Question

Какие клиентские версии/браузерные сценарии входят в поддерживаемую систему?

### Why it matters

Совместимость routes, auth interceptors и UX.

Источник вопроса: A17 H3; B17 Q9.

## OPEN-SRV-021 — Storage boundary

**Type:** ARCHITECTURE_DECISION

### Current As-Is

Байты локальные; S3/storage adapter нет.

### Question

Какая физическая модель хранения является продуктовой/эксплуатационной границей?

### Why it matters

System deployment и ownership storage.

Источник вопроса: A17 G4.

## OPEN-SRV-022 — Backup и retention

**Type:** ARCHITECTURE_DECISION

### Current As-Is

Код не задаёт согласованного backup/restore DB+FS. Наличие реального внешнего backup в deployment не установлено — DEPLOYMENT_UNKNOWN; это отдельный фактический вопрос, а backup/retention/RPO policy — архитектурное решение этого OPEN.

### Question

Каковы политика backup/retention и допустимое окно потери данных (RPO)?

### Why it matters

Эксплуатационная часть System Specification.

Источник вопроса: A17 G3; B17 Q5.

## OPEN-SRV-023 — Нагрузка и capacity

**Type:** PRODUCT_DECISION

### Current As-Is

Per-file100MiB и batch500; нет quota/верхнего page size.

### Question

Каковы объёмы архива, число пользователей/transfers и требования доступности?

### Why it matters

Limits, capacity и operational acceptance.

Источник вопроса: A17 G5; B17 Q8.

## OPEN-SRV-024 — Ошибки в клиентском продукте

**Type:** ARCHITECTURE_DECISION

### Current As-Is

Move conflict может дать500;501 Map; сообщения смешанных языков.

### Question

Как UI различает конфликты/сбои и какой пользовательский contract языка/ошибок требуется?

### Why it matters

Совместная error semantics без навязанного retry алгоритма.

Источник вопроса: A17 F1–F3; B17 Q16.

## OPEN-SRV-025 — Развёрнутая версия и legacy данные

**Type:** DEPLOYMENT_UNKNOWN

### Current As-Is

Спецификация описывает source и результат migrations01–14; live DB не читалась.

### Question

Какие changesets фактически применены и какие legacy/duplicate данные существовали при upgrade?

### Why it matters

Применимость As-Is к deployment и истории данных.

Источник вопроса: A17 G1; B17 Q1/Q2/Q3.

## OPEN-SRV-026 — Volumes и working directory

**Type:** DEPLOYMENT_UNKNOWN

### Current As-Is

root/temp конфигурируемы; relative root зависит от cwd.

### Question

Каковы реальные roots, mounts, shared volumes и свойства FS?

### Why it matters

Durability/atomic move и разрешение существующих paths.

Источник вопроса: B17 Q4.

## OPEN-SRV-027 — TLS и email origin

**Type:** DEPLOYMENT_UNKNOWN

### Current As-Is

Ссылки из request scheme/host; proxy/TLS policy проекта отсутствует.

### Question

Какие внешние TLS/forwarded-host/origin правила действуют?

### Why it matters

Transport security и корректность email links.

Источник вопроса: A17 G2; B17 Q6.

## OPEN-SRV-028 — Передача environment

**Type:** DEPLOYMENT_UNKNOWN

### Current As-Is

Placeholders есть; встроенная загрузка env-файлов не установлена.

### Question

Как launcher передаёт environment и какие override реально действуют?

### Why it matters

Воспроизводимость configuration без раскрытия секретов.

Источник вопроса: B17 Q7.

## OPEN-SRV-029 — Framework download contract

**Type:** FRAMEWORK_UNKNOWN

### Current As-Is

Controller возвращает200 Resource; собственных Range/conditional branches нет.

### Question

Каков фактический wire-контракт Range/206/416, HEAD/OPTIONS и conditional/cache headers в используемом runtime?

### Why it matters

Транспортное сопоставление с Android; предел статической проверки.

Источник вопроса: VER-SRV-012; A07 §12; B05 framework.

## OPEN-SRV-030 — Framework error dispatch

**Type:** FRAMEWORK_UNKNOWN

### Current As-Is

Нет catch-all/IO handler; /error не permitAll.

### Question

Каковы точные wire-status/body для multipart missing-part, conversion и необработанных ошибок в deployed stack?

### Why it matters

Надёжное распознавание errors за пределами ErrorResponse.

Источник вопроса: VER-SRV-011; A10; B10.
