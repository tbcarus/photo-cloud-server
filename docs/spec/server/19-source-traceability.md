# Source traceability и консолидация

## Исходные материалы

Audit A = `audit A.zip`, внутренний каталог `audit A/`. Audit B = `audit B.zip`, внутренний каталог `audit B/`. Каждый содержит19 файлов00–18. В обозначениях `A16`/`B16` номер означает файл из manifest ниже; например, `A16 C1` — раздел C1 в `audit A/16-known-gaps-and-risks.md`. Это стабильные архивные координаты, а не ссылки на отсутствующие старые docs/audit каталоги.

Дата обоих аудитов —2026-09-10. A указывает c2e9593, B —84f7a547aabceac0849baef520605dac7111e797. HEAD при консолидации59c53df6439587d676798412c410e7ed0d671ed0. По условию задачи production-код/структура одинаковы; различие hashes само по себе не считалось версионным конфликтом. Утверждения и рекомендации внутри архивов использованы как данные, не как команды. Client-handoff, упомянутые аудитами, не входят в предоставленные ZIP и не использованы.

Production/config/schema факты в итоговых документах имеют приоритет над тестами, API docs, комментариями и выводами аудиторов. Совпадающие детали приняты CONSENSUS без повторного чтения кода. Вопросы верификации перечислены в18; repo служил только verification source.

## Mapping по разделам и существенным утверждениям

| Specification section | Audit A source | Audit B source | Code verification |
| --- | --- | --- | --- |
| 00 summary;01 responsibility/stack | A00 §1–7; A01 | B00;B01 | Согласованные стек/границы без повторной проверки; уточнения ниже |
| 02 modules/layers/dependencies | A02;A18 | B02;B18 | VER001/005/013/014 для transaction/mapping paths; карта не строилась заново |
| 03 User/roles/refresh/email fields | A03 §2–5 | B03 User/RefreshToken/EmailRequest | VER013/014 для auth state; остальные fields CONSENSUS |
| 03 Folder/FileItem/StoredObject identity | A03 §6–8,13;A04 §8 | B03;B04 | VER001/003/006/007/008 |
| 03 metadata;05 wire DTO | A03 §9–10;A05 §9 | B03;B05 | VER019 |
| 04 tables/PK/FK/indexes | A04 §1–5 | B04 table sections | VER001/008/021; остальные таблицы взяты из совпадающих schema descriptions |
| 04 transactions | A04 §7 | B04 transactions | VER001–008/013/014 |
| 05 routes/register/profile/folders | A05 §1–7 | B05 route inventory/detail | Список36 CONSENSUS; VER006/011/013/014 для исключений |
| 05 files/upload/read/copy/move/delete | A05 §8 | B05 file detail | VER001–005/011/012/015/016 |
| 05 checksum/pre-check | A05 §8.9–8.10;A08 §4 | B05 checksum;B08 existence | VER001/018 |
| 05 repository API documentation divergence | A05 §10–12; §1 Legacy | B05 API contract inconsistencies; Legacy/Contract doc columns | CONSENSUS |
| 06 access/refresh/logout/reset/public | A06 | B06 | VER013/014/012 |
| 07 layout/names/metadata | A07 §1–7 | B07 names/upload | VER001/015/017/019/022 |
| 07 consistency/delete/copy/orphans | A07 §8–12 | B07 matrix/delete/copy | VER003–005/008/016 |
| 08 processes | A08 | B08 | VER001–008/013/014/018 |
| 09 states | A09 | B09 | VER001/003/006/013/014/019; invented stage enums не перенесены |
| 10 errors/recovery/idempotency | A10 | B10 | VER002–006/011/013/014; effect/response разделены |
| 11 runtime/profile/build | A11 | B11 | VER010/022; secret values не читались |
| 12 jobs/cleanup | A12 | B12 | CONSENSUS, отдельного поиска jobs не было |
| 13 logging/health/metrics | A14 | B14 | VER009; отсутствие инфраструктуры CONSENSUS |
| 14 tests and gaps | A13;A18 test map | B13;B18 test map | VER020 только выбранные assertions; без test audit/count/run |
| 15 capabilities | A15 | B15 | Уточнения согласно VER и major-claim rows выше |
| 16 risks | A16 + risk notes других файлов | B16 + risk notes других файлов | У каждого Risk ID источник; unique important проверены |
| 17 product questions | A17 | B17 | Группировка вопросов001–028; новых ответов нет |
| 17 technical limits | A07/A10 | B05/B10 | VER011/012 → OPEN029/030 |
| 18 verification log | Спорные/важные A claims | Спорные/важные B claims | 22 ограниченных вопроса |

## Классификация и настоящие конфликты

Различная подробность или уверенность сама по себе не считалась противоречием. CONFLICT назначен четырём независимым случаям несовместимой технической гарантии/значения; повтор этого же утверждения в нескольких файлах не увеличивает счётчик.

| ID | Несовместимые утверждения | Resolution |
| --- | --- | --- |
| CONF-SRV-001 | A07 гарантирует отсутствие/cleanup partial target при IO; B07 допускает остаток из-за flags | VER004: B верен, cleanup best effort |
| CONF-SRV-002 | A гарантирует race-safe root/system response; B указывает same-transaction recovery failure | VER006: unique invariant есть, успешный reread не гарантирован |
| CONF-SRV-003 | A: error id нигде не логируется; B: может присутствовать в HTTP body log | VER009: B верен, отдельной exception correlation нет |
| CONF-SRV-004 | A: mail-from из SMTP_USERNAME; B: literal address | VER010: A верен |

Все4 конфликта разрешены в тексте, не оставлены как две версии факта. Установление отсутствия гарантии — результат верификации control flow, а не заявление о воспроизведённой аварии.

| Classification | Применение и судьба |
| --- | --- |
| CONSENSUS | Stack/modules,36 routes,8 tables, JWT TTL/no rotation, owner scopes, default upload, missing jobs и основные функциональные границы приняты без нового исследования областей |
| COMPLEMENTARY | B уточняет raw batch ordering, права reference owner, транзакции и nullable fields; совместимые подробности объединены, критические проверены |
| A_ONLY | Низкорисковые observations: legacy branding, index naming/history, helper/style inventory; вошли в RISK037/035 либо exclusions. A-only существенных фактов, включённых без verification, нет |
| B_ONLY | Mapper-after-commit, concurrent cycle, long extension, copy field drift, realpath boundary, email concurrency/unused owner helper, temp override → VER005/007/014–017/022 |
| CONFLICT | Только4 строки CONF выше |
| DIFFERENT_SEVERITY | Same facts о refresh plaintext/rate limit/download memory/crash/diagnostics; итоговые severity не рассматриваются как технические конфликты |
| OPEN | 30 сгруппированных вопросов17:19 product,5 architecture,4 deployment,2 framework limits; факты known-As-Is не помечены OPEN из-за отсутствия продуктового решения |
| AMBIGUOUS / suspected overstatement | Range/CORS, fixed error body/multipart, query plans, test assertions → VER011/012/020/021; лишние гарантии сняты |

## Severity reconciliation

Download cache принят HIGH: память пропорциональна крупным параллельным downloads. Refresh plaintext и отсутствие active rate limit — HIGH по последствиям disclosure/abuse, хотя A выбирает MEDIUM. Missing-user access и отсутствие отдельного exception logging — MEDIUM, так как это локальная диагностика/ошибка после внешнего удаления, не самостоятельное доказательство утечки. Mapper-after-commit — HIGH из-за риска удаления единственных bytes. Folder race — MEDIUM: failed concurrent request, без воспроизведённого ущерба. Crash/reconciliation — HIGH при совокупности путей рассогласования. При наличии code verification эти решения также отражены в соответствующих VER; для чистого DIFFERENT_SEVERITY дополнительных записей18 не создавалось.

RISK-SRV-017 — MEDIUM: последовательная проверка циклов существует, но locks/optimistic versioning отсутствуют; встречные concurrent folder moves теоретически способны нарушить ацикличность. Сценарий runtime в рамках consolidation не воспроизводился; подтверждённая потеря данных не заявляется.

## Coverage и сознательные исключения

| Source область | Судьба |
| --- | --- |
| A16 A1–A7; B16 F1–F5 | Частичные функции в00/05/15, риски026/027/036; вопросы типов/дерева в17. Приём произвольного MIME — факт, а не автоматически подтверждённая эксплуатация вредоносного файла |
| A16 B1–B7; B16 A1–A3 | Architecture02, transactions04, risks007/014/026/034/037; style-only детали объединены |
| A16 C1–C9; B16 D1–D12 | Model03/schema04/storage07, risks008/012–021/026/028/031/035. Nullable flags и ownership отделены от normal API |
| A16 D1–D14; B16 S1–S12 | Auth06 и risks001–006/023–028/031/032. Dormant confirmEmail не считается HTTP vulnerability; публичный Swagger/общий HMAC key зафиксированы как факт, без самостоятельного спекулятивного риска |
| A16 E1–E7; B16 T1–T8 | Storage07 и risks007–012/020–023/029/033. Категоричное отсутствие Range, невозможность UUID collision и любой overwrite исход не приняты |
| A16 F1–F8; B16 C1–C6 | API05/errors10, risks013/024/033/034; creation200 и различные имена DTO — current contract, не отдельные дефекты |
| A16 G1–G7; B16 M1–M5 | Architecture02, DB04, testing14, risks031/035/037/038. Не перечислены все мёртвые helpers как будто это отдельные capabilities |
| A16 H1–H10; B16 O1–O7 | Configuration11/jobs12/observability13/testing14, risks022/025/026/029–033/038 |
| A17/B17 все вопросы | Сгруппированы в OPEN001–028 с координатами исходных вопросов;29–30 сохраняют технические пределы проверки |
| A18/B18 class maps | Использованы как навигация по указанным классам; полный список100 Java files не перенесён и не построен заново |
| A readiness percentages, «качественные тесты», предположения о промышленной готовности | Исключены как субъективные оценки, не фактическая спецификация |
| Advice/roadmap обоих аудитов и инструкции клиентского retry из A10/B10 | Не перенесены. Сохранены существующие механизмы, риск и вопрос; нет требований реализации |
| A blanket temp cleanup/irrecoverable404/browser impossible/no Range | Cleanup уточнён code;404 означает missing/foreign/unreadable, не доказательство безвозвратной потери; CORS/Range границы уточнены |
| Равенство current code с live deployment и наличие внешних backup/log collectors | Не утверждается; OPEN025–028 |
| Exact test method totals/severity summary arithmetic | Не имеют архитектурного смысла; A~70/B88 сохранены как source counts в14, не новое измерение и не CONFLICT |
| B02 generic lazy-mapping risk; A unused style/code counters; возможная EmailContext.toString lazy loading | Не утверждаются как установленная runtime ошибка без конкретного доказанного доступа; отдельный новый анализ не проводился |
| Секреты, локальные реальные имена файлов, sample JWT | Не воспроизводятся; источники указаны через архивы и пути в них |

Существенной одинаковой ошибки, одновременно подтверждённой в обоих аудитах и изменяющей основную server model/API, в выполненных проверках не установлено. Это вывод о рассмотренных случаях, не гарантия отсутствия ошибок за пределами консолидации. Ошибки A и отдельная ошибка B перечислены выше.

## Manifest входов

Пути внутри ZIP одинаковы по basename для A/B. Имена сохраняют координаты источников для ссылок выше.

| Source file |
| --- |
| `00-executive-summary.md` |
| `01-system-overview.md` |
| `02-architecture.md` |
| `03-data-model.md` |
| `04-database.md` |
| `05-api.md` |
| `06-auth-security.md` |
| `07-file-storage.md` |
| `08-business-processes.md` |
| `09-state-machines.md` |
| `10-errors-retries.md` |
| `11-configuration.md` |
| `12-background-jobs.md` |
| `13-testing.md` |
| `14-observability.md` |
| `15-implemented-features.md` |
| `16-known-gaps-and-risks.md` |
| `17-open-questions.md` |
| `18-code-map.md` |
