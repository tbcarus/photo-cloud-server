# 07. Расхождения документации и реализации

[CONFIRMED] Точный файл api-contract.md не найден. Вместо него существуют docs/api-user-contract.md, api-file-contract.md, api-folder-contract.md, api-checksum-sync-contract.md. Они сверены вместе с docs/application-overview.md, README.md, README-description.md, TODO.txt и docs/http-tests. Документация не переписывалась.

[CONFIRMED] Основные современные маршруты, DTO, folder scope checksum, happy-path upload/copy/delete и JWT20min/7days в четырёх api-*-contract.md совпадают с кодом. Ниже отдельно перечислены противоречия, недостающие оговорки и устаревшие планы. План/TODO не трактуется как обещанная уже реализованная функция.

| Area | Documentation says | Server actually does | Severity |
| --- | --- | --- | --- |
| [INCONSISTENCY] Move checksum error | api-file-contract: move errors400/404/409; checksum500 не описан | При target checksum conflict без name conflict save нарушает14 unique →500 DATABASE_CONSTRAINT_VIOLATION | HIGH |
| [INCONSISTENCY] Generic error envelope | api-user-contract предлагает общий ErrorResponse | Конкретные handlers only; IOException/runtime/framework не covered;501 только message | MEDIUM |
| [INCONSISTENCY] Public auth with Bearer | Public routes обозначены без оговорки header | Expired/malformed Bearer вызывает401 до public handler, в т.ч. refresh | HIGH |
| [INCONSISTENCY] Error status «disabled/banned» | api-user-contract summary401 для disabled/banned | Проверяются login, но не имеющийся access или refresh | HIGH |
| [INCONSISTENCY] Folder invariant «ровно один ROOT» | api-folder-contract/application-overview: у user один ROOT | До первого обращения может быть0; SQL обеспечивает at most one ROOT, не existence | LOW |
| [INCONSISTENCY] Parent null only ROOT | api-folder-contract утверждает модельный invariant | Service так создаёт; SQL не запрещает non-ROOT parent=null | LOW |
| [INCONSISTENCY] Atomic/cleanup robustness | api-file-contract pipeline: cleanup при ошибках | Best effort; kill не ловится; partial copy/fallback move не всегда удаляют target | MEDIUM |
| [INCONSISTENCY] Email send error | api-user-contract: email-send ошибка логируется, создание не откатывается | Catch только MessagingException; MailException/runtime не handled; cause.getMessage может NPE; rows уже сохранены | MEDIUM |
| [INCONSISTENCY] Mail reset user journey | README «сброс через email» | JSON confirm есть, письмо ведёт на page501 | HIGH |
| [INCONSISTENCY] Smoke completeness | http-tests/README: .http для всех current endpoints | Нет6 folder routes, explicit upload и file rename/move/copy | MEDIUM |
| [INCONSISTENCY] curl equivalents | http-tests/README обещает аналогичные curl примеры | api-curl-examples.md содержит только variables/header, ни одной curl-команды | LOW |
| [INCONSISTENCY] Smoke delete ordering | .http comment: physical file, затем DB | DB transaction first, filesystem second; storageKey column больше нет | MEDIUM |
| [INCONSISTENCY] Smoke reset token revocation | .http comment предлагает проверить «все refresh отзываются» | Reset их не отзывает; api-user-contract верно отмечает это | HIGH |
| [INCONSISTENCY] Smoke folderId | checksums/exists example использует fileItemId как folderId | Это разные ID spaces, случайное совпадение не контракт | MEDIUM |
| [INCONSISTENCY] Smoke token capture | README: login сохранит tokens в IntelliJ globals | В .http нет response script; вместо этого hardcoded JWT-like variables | MEDIUM |
| [INCONSISTENCY] Smoke uploadFile | README предлагает изменить @uploadFile | Active file inclusion в multipart использует отдельный literal local path | LOW |
| [INCONSISTENCY] List params | http-tests/README: only page/size; type/from/to future | Current controller также folderId; type/from/to не используются и не указаны active list example | LOW |
| [INCONSISTENCY] README folder roadmap | README folders listed future | Folder API уже реализован; Albums всё ещё отсутствует | LOW |
| [DOCUMENTED] Docker/.env launch | README описывает Docker окружения и .env | Локальные .env найдены, значения недоступны; tracked Dockerfile/compose/loader не найден | MEDIUM |
| [INCONSISTENCY] Historical token lifetime | TODO: Access2h, Refresh2months/indefinite | Code20min/7days; это прежний вопрос/план, не current API | MEDIUM |
| [INCONSISTENCY] Reset lifetime | README-description TODO:1day | EmailRequest expiry3days для обоих типов | MEDIUM |
| [DOCUMENTED] Email frequency cap | README-description TODO:<=3requests/3days | Helper есть, active flows его не вызывают, resend501 | MEDIUM |
| [DOCUMENTED] Client local sync states | README-description предлагает NEW/CHECK/UPLOAD/LIMIT | Server этих status не принимает; quota-error API нет | MEDIUM |
| [INCONSISTENCY] Metadata duration | application-overview говорит duration «если удалось извлечь» | DTO/entity field есть, extractor durationSec не задаёт | LOW |
| [INCONSISTENCY] Generic server-side copy TODO | api-file-contract/upload section TODO «copy без bytes — будущий endpoint» | Copy по известному FileItem ID уже есть; отсутствует именно link-existing/by-checksum endpoint | LOW |
| [INCONSISTENCY] Roles support wording | README «разделение ролей» | USER/ADMIN данные есть, role-specific endpoint rules нет | MEDIUM |
| [INCONSISTENCY] Все ID Long | TODO отмечает завершение | Entities Long, но RefreshToken/EmailRequest repositories Integer ID | LOW |

[CONFIRMED] Отдельно без противоречия: api-checksum-sync-contract правильно описывает user+folder scope, max500 raw count (service), lowercase/order/dedup и отсутствие полного sync; api-file-contract правильно предупреждает copy same folder409, hard delete и physical fields hidden; api-user-contract правильно описывает refresh без rotation и reset без revoke. Аудит не объявляет весь существующий контракт устаревшим.

[INFERRED] Ни комментарии .http «OK», ни имена тестов не доказывают прохождение запросов на текущем server revision. Не определено, на какие именно документы ориентируется текущий Android-клиент.

Источники документов: [API file](C:/projects/photo-cloud-server/docs/api-file-contract.md), [API checksum](C:/projects/photo-cloud-server/docs/api-checksum-sync-contract.md), [API folder](C:/projects/photo-cloud-server/docs/api-folder-contract.md), [API user](C:/projects/photo-cloud-server/docs/api-user-contract.md), [HTTP examples](C:/projects/photo-cloud-server/docs/http-tests), [README-description](C:/projects/photo-cloud-server/README-description.md), [TODO](C:/projects/photo-cloud-server/TODO.txt). Код сопоставлен по [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [services](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service), [migrations](C:/projects/photo-cloud-server/src/main/resources/db/changelog/table).
