# Риски текущей реализации

Риски описывают возможные последствия установленного поведения, а не произошедшие инциденты. HIGH — потенциальная утрата данных/доступа, раскрытие credentials или серьёзный отказ; MEDIUM — ограниченная целостность, contract/operational failure; LOW — сопровождение и локальные ограничения. CRITICAL не назначен: сценарий критического инцидента в рамках этих источников не подтверждён. Это не CVSS.

Обозначения A16/B16 и другие ссылки расшифрованы в [19](19-source-traceability.md). Различия severity не считаются CONFLICT. Части одного риска объединены по последствию; направления реализации и roadmap сюда не включены.

## RISK-SRV-001

**Area:** Security/logging

**Description:** JSON credentials, токены, query-коды и HTML писем выводятся без masking; часть содержимого файлов тоже попадает в логи.

**Evidence:** A16 D1/E5; A14 §4–5; B16 S1/O1; VER-SRV-009

**Consequence:** Читатели логов получают чувствительные данные и bearer credentials.

**Severity:** HIGH

## RISK-SRV-002

**Area:** Security/account

**Description:** Enabled/banned повторно не проверяются access/refresh; reset не отзывает существующие токены.

**Evidence:** A16 D3/D4; B16 S2/S3; VER-SRV-013/014

**Consequence:** Смена пароля или ban не прекращают ранее полученный доступ.

**Severity:** HIGH

## RISK-SRV-003

**Area:** Security/tokens

**Description:** Refresh хранится полной строкой и не ротируется; нет reuse detection.

**Evidence:** A16 D5; B16 S7; VER-SRV-013

**Consequence:** Компрометация DB/токена сохраняет bearer доступ до expiry/revoke.

**Severity:** HIGH

## RISK-SRV-004

**Area:** Security/revoke

**Description:** Access не отзывается logout; чтение refresh и revoke не сериализованы.

**Evidence:** A16 D9; B16 S4; VER-SRV-013

**Consequence:** Окно доступа до exp; конкурентно может быть выдан access.

**Severity:** MEDIUM

## RISK-SRV-005

**Area:** Security/limits

**Description:** Нет active rate limiter; password policy только4..20.

**Evidence:** A16 D7/D8; B16 S5/S10; A/B06; VER-SRV-014

**Consequence:** Перебор, SMTP abuse и рост token/code rows; допустимы слабые пароли.

**Severity:** HIGH

## RISK-SRV-006

**Area:** Security/enumeration

**Description:** Register409 и unknown reset400 раскрывают наличие email.

**Evidence:** A16 D6; B16 S6; A/B05

**Consequence:** Account enumeration.

**Severity:** MEDIUM

## RISK-SRV-007

**Area:** Storage/atomicity

**Description:** DB и FS не имеют общей transaction, cleanup best effort и нет reconciliation.

**Evidence:** A16 C5/E2; B16 A1/T1; VER-SRV-001/004

**Consequence:** Crash/cleanup failure оставляет temp, orphan или недоступный файл.

**Severity:** HIGH

## RISK-SRV-008

**Area:** Storage/mapping

**Description:** DTO mapping после commit охвачен cleanup runtime catch.

**Evidence:** B16 D8; VER-SRV-005

**Consequence:** Mapper error может удалить bytes при сохранённых DB rows.

**Severity:** HIGH

## RISK-SRV-009

**Area:** Storage/partial IO

**Description:** Flags success устанавливаются после move/copy; при частичном IO target cleanup не гарантирован.

**Evidence:** A07 §8; B16 T4/T5; VER-SRV-004

**Consequence:** Partial target/orphan после неуспешного IO.

**Severity:** MEDIUM

## RISK-SRV-010

**Area:** Storage/existence

**Description:** Exists и duplicate upload проверяют только DB; download не пересчитывает checksum.

**Evidence:** A07 §10.5; B16 T2; VER-SRV-001/018

**Consequence:** Existing/200 не подтверждают физическую сохранность и не восстанавливают bytes.

**Severity:** HIGH

## RISK-SRV-011

**Area:** Storage/delete

**Description:** Physical delete идёт после DB commit; IO error только log.

**Evidence:** A16 C5; B16 T3; VER-SRV-003

**Consequence:** 204 при оставшихся bytes; повтор по прежнему ID не очистит их.

**Severity:** MEDIUM

## RISK-SRV-012

**Area:** Ownership/delete

**Description:** Owner-delete уничтожает все references; прямой user SQL-delete не вызывает FS cleanup; cross-owner FK может блокировать SQL-delete.

**Evidence:** A16 C3/C8; B04 удаления; VER-SRV-003/008

**Consequence:** Утрата общих logical references либо orphan bytes при внешних действиях над DB.

**Severity:** HIGH

## RISK-SRV-013

**Area:** API/move

**Description:** Move проверяет name, но не checksum target; unique нарушение маппится500.

**Evidence:** A16 C1/F2; B16 C1; VER-SRV-002

**Consequence:** Обычный конфликт содержимого выглядит как server failure.

**Severity:** HIGH

## RISK-SRV-014

**Area:** Integrity/names

**Description:** File name uniqueness вне CAMERA проверяется read-check-write без DB unique.

**Evidence:** A16 B3/C6; B16 D4; VER-SRV-001/002

**Consequence:** Параллельные операции могут сохранить одинаковые logical names.

**Severity:** MEDIUM

## RISK-SRV-015

**Area:** Integrity/checksum

**Description:** FileItem.checksum и StoredObject.checksum синхронизируются кодом, а не DB constraint.

**Evidence:** A16 C2; B16 D3; VER-SRV-001/018/019

**Consequence:** DTO/full list и exists/unique могут расходиться при drift.

**Severity:** MEDIUM

## RISK-SRV-016

**Area:** Integrity/folders

**Description:** Системный reread после unique violation выполняется в той же transaction; поздняя ошибка save может миновать catch.

**Evidence:** A00 §9; B16 D5; VER-SRV-006

**Consequence:** Конкурентный bootstrap/rename способен завершиться ошибкой вместо ожидаемого reread/409.

**Severity:** MEDIUM

## RISK-SRV-017

**Area:** Integrity/tree

**Description:** Последовательная проверка циклов по parent chain существует; locks/optimistic versioning отсутствуют. SQL CHECK запрещает только self-parent.

**Evidence:** B16 D6; VER-SRV-007

**Consequence:** Встречные concurrent folder moves теоретически способны нарушить ацикличность дерева. Сценарий runtime не воспроизводился в рамках consolidation.

**Severity:** MEDIUM

## RISK-SRV-018

**Area:** Integrity/ownership

**Description:** Одиночные FK не связывают owner Item/Folder/Object или parent owner.

**Evidence:** A04 §8.4; B16 D7; VER-SRV-008

**Consequence:** Некорректные internal/manual строки допускают cross-owner связи.

**Severity:** MEDIUM

## RISK-SRV-019

**Area:** Migrations

**Description:** Migration10 DROP media_file без переноса; последующие backfill/unique требуют совместимых данных.

**Evidence:** A04 §2; B16 D1/D2; VER-SRV-008; A/B04

**Consequence:** Потеря legacy metadata либо failure startup migration на непустой DB.

**Severity:** HIGH

## RISK-SRV-020

**Area:** Storage/names

**Description:** Long extension может нарушить logical length255; character limit не равен FS byte limit.

**Evidence:** B16 D9; VER-SRV-015

**Consequence:** DB/IO отказ для имени, прошедшего normalizer.

**Severity:** MEDIUM

## RISK-SRV-021

**Area:** Storage/copy

**Description:** Copy наследует checksum/size/MIME/extension без анализа bytes; новое имя может расходиться с extension column.

**Evidence:** B16 T7/D10; VER-SRV-016

**Consequence:** Повреждённые bytes копируются с прежней metadata; расходятся physical поля.

**Severity:** MEDIUM

## RISK-SRV-022

**Area:** Storage/durability

**Description:** Нет fsync/durable journal; root/temp могут различаться по FS.

**Evidence:** A16 E3/E4; B16 T5/O6; VER-SRV-004/022

**Consequence:** Потеря ожидаемой atomic move/durability при отказе.

**Severity:** MEDIUM

## RISK-SRV-023

**Area:** Storage/path

**Description:** Path guard лексический, без realpath/symlink проверки.

**Evidence:** B16 T6; VER-SRV-017

**Consequence:** Локальная подмена каталогов находится вне защиты resolver.

**Severity:** MEDIUM

## RISK-SRV-024

**Area:** Security/transport

**Description:** TLS/CORS/proxy policy приложения не задана; email origin берётся из request.

**Evidence:** A16 D14; B16 S8/S12; VER-SRV-012/014

**Consequence:** Без подходящего внешнего транспорта/host контроля возможны раскрытие данных и неверные email links.

**Severity:** MEDIUM

## RISK-SRV-025

**Area:** Auth/missing user

**Description:** Access principal lookup использует Optional.get вне JWT catch.

**Evidence:** A16 D2; B16 O7; VER-SRV-013

**Consequence:** Нет стабильного401 после удаления user; необработанная ошибка.

**Severity:** MEDIUM

## RISK-SRV-026

**Area:** Email/transactions

**Description:** Register/login неатомарны; SMTP синхронный, без явного timeout/retry; MessagingException catch разыменовывает cause.

**Evidence:** A16 A1/H7/H8; B16 D12/O3; VER-SRV-014

**Consequence:** User/code/lastLogin могут сохраниться при последующем отказе; false success доставки либо NPE.

**Severity:** MEDIUM

## RISK-SRV-027

**Area:** Email/recovery

**Description:** Reset email ведёт на501; resend отсутствует.

**Evidence:** A16 A1/A2; B16 F1/F2; A/B05/06

**Consequence:** Обычный переход из письма не завершает reset; потерянная activation-ссылка не перевыпускается API.

**Severity:** HIGH

## RISK-SRV-028

**Area:** Email/concurrency

**Description:** Одноразовость code проверяется флагом без lock/version/conditional update.

**Evidence:** B16 D11; VER-SRV-014

**Consequence:** Два concurrent confirm/reset могут пройти проверку used=false.

**Severity:** MEDIUM

## RISK-SRV-029

**Area:** Observability/memory

**Description:** Response wrapper кэширует download целиком, включая binary; log text limit память не ограничивает.

**Evidence:** A16 E5; B16 O1; VER-SRV-009

**Consequence:** Heap растёт с размером и числом downloads; возможен OOM.

**Severity:** HIGH

## RISK-SRV-030

**Area:** Observability/errors

**Description:** Advice не пишет exception/stacktrace; error id лишь может быть в HTTP body log; нет durable audit/metrics/readiness.

**Evidence:** A16 H1/H2/H3; B16 O4; VER-SRV-009

**Consequence:** Ограниченная диагностика и позднее обнаружение отказов/нарушений целостности.

**Severity:** MEDIUM

## RISK-SRV-031

**Area:** Persistence/scale

**Description:** Token lookup без индекса token и FK user; token/code rows не очищаются; repository ID Integer при Long entities.

**Evidence:** A16 C4/G3/H9; B16 M1/O5; A/B03/04

**Consequence:** Рост данных и стоимость lookup; orphan token rows; несоответствие generic CRUD типов.

**Severity:** MEDIUM

## RISK-SRV-032

**Area:** Build/secrets

**Description:** Аудиты фиксируют env-файлы в resources и JWT-подобные literals в HTTP examples; значения/действительность не проверялись.

**Evidence:** A16 D13; B11; B16 S9

**Consequence:** При включении resources в build возможна упаковка секретов; примеры могут распространять credential-like данные.

**Severity:** MEDIUM

## RISK-SRV-033

**Area:** Capacity

**Description:** Нет общей quota/disk admission; size list сверху не ограничен; full checksums без pagination; physical dedup только внутри folder upload.

**Evidence:** A16 C9/E1/F8; B16 T8/O2; A/B05/07

**Consequence:** Рост занимаемого диска и тяжёлые memory/DB запросы.

**Severity:** MEDIUM

## RISK-SRV-034

**Area:** API/representation

**Description:** Нет общего body для всех IO/framework/501 ошибок; date без offset; attachment без filename*; framework Range не установлен. Public auth endpoint может быть отклонён JWT filter с401 при переданном malformed/expired Bearer до controller, в том числе в refresh flow.

**Evidence:** A16 B4/F1/F6/F7; B16 C2/C3/C5/C6; VER-SRV-011/012/019

**Consequence:** Неоднозначная обработка ошибок, дат и Unicode имени разными клиентами; refresh-запрос с приложенным malformed/expired access Bearer может завершиться401 до проверки refresh token.

**Severity:** MEDIUM

## RISK-SRV-035

**Area:** Maintenance/schema

**Description:** Entity lengths/precision неполны; nullable revoked/role допускают несовместимые внешние данные; folder indexes перекрываются; migration rollback нет.

**Evidence:** A16 C7/G4/G5/G6; B16 M4; A/B04

**Consequence:** Ошибка внешней записи/ORM mapping; лишняя стоимость индексов, сложность восстановления upgrade.

**Severity:** MEDIUM

## RISK-SRV-036

**Area:** Product/media/sync

**Description:** Video metadata не извлекается; нет delta/tombstones/devices; CAMERA ID отсутствует до default upload; часть profile/settings STUB.

**Evidence:** A16 A4; B16 F2–F5; A/B15; VER-SRV-006/019

**Consequence:** Клиент не получает полный media/sync/account workflow из существующих endpoints.

**Severity:** MEDIUM

## RISK-SRV-037

**Area:** Maintainability

**Description:** Обратные package dependencies, смешанный mapping, неиспользуемые helpers, legacy branding и неполные ручные примеры.

**Evidence:** A16 B1/B2/B5–B7/G1/G2/G7; B16 A2/M3/M5/C4; A/B02/13/15

**Consequence:** Ошибочное понимание контракта и лишняя стоимость сопровождения; branding влияет на доверие письмам.

**Severity:** LOW

## RISK-SRV-038

**Area:** Operations/testing

**Description:** Нет supplied CI/coverage/deploy/backup procedures; stdout retention не задана; ряд assertions не доказывает название сценария.

**Evidence:** A16 H4/H5/H6/H10; B16 M2/O4; A/B13/14; VER-SRV-020

**Consequence:** Не подтверждены автоматическая регрессия, сохранность логов и согласованное восстановление DB/FS.

**Severity:** MEDIUM
