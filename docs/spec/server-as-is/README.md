# Verified Server As-Is Specification — PhotoCloud

- Version: `Server As-Is v1`
- Status: `FROZEN`
- Review: `PASS_WITH_MINOR_FIXES → all 11 findings closed`
- Freeze record: [20-freeze-record.md](20-freeze-record.md)

Этот набор описывает фактическое устройство серверной части: API, данные, хранение, безопасность и ограничения. Он предназначен для сопоставления с Android As-Is и построения общей System As-Is Specification.

Основа — консолидация Audit A и Audit B одной production-версии. Код использован только для вопросов, перечисленных в журнале верификации. Дата консолидации: 2026-09-11. Состояние развёрнутого сервера, доставка SMTP, действующие переменные окружения и результаты запуска тестов не подтверждались. Описание БД относится к результату применения поставляемых миграций 01–14.

Обычный текст означает AS-IS. PARTIAL обозначает частичную функцию, STUB — заглушку, UNUSED — существующий неиспользуемый элемент, OPEN — предел установленного поведения или нерешённый вопрос. В API и матрице дополнительно используются IMPLEMENTED и NOT PRESENT. IMPLEMENTED не означает отсутствие рисков или подтверждённое прохождение тестов.

Начало чтения: [сводка](00-server-as-is-summary.md), затем [API](05-api.md), [модель данных](03-data-model.md) и [хранилище](07-file-storage.md). Стабильные идентификаторы capabilities находятся в [матрице](15-feature-matrix.md). Риски, вопросы и происхождение фактов вынесены отдельно.

## Состав

| Файл | Назначение |
| --- | --- |
| [00-server-as-is-summary.md](00-server-as-is-summary.md) | Краткая сводка |
| [01-system-overview.md](01-system-overview.md) | Границы и источники данных |
| [02-architecture.md](02-architecture.md) | Слои и зависимости |
| [03-data-model.md](03-data-model.md) | Сущности, identity, ownership, ER |
| [04-database.md](04-database.md) | Схема, ограничения, транзакции |
| [05-api.md](05-api.md) | Все 36 прикладных HTTP mappings и DTO |
| [06-auth-security.md](06-auth-security.md) | Аутентификация и авторизация |
| [07-file-storage.md](07-file-storage.md) | Байты, deduplication, lifecycle |
| [08-business-processes.md](08-business-processes.md) | Сквозные процессы |
| [09-state-model.md](09-state-model.md) | Поля и фактические состояния |
| [10-errors-and-recovery.md](10-errors-and-recovery.md) | Ошибки и свойства повторов |
| [11-configuration.md](11-configuration.md) | Runtime configuration без секретов |
| [12-background-processing.md](12-background-processing.md) | Синхронная работа и отсутствие jobs |
| [13-observability.md](13-observability.md) | Логи и наблюдаемость |
| [14-testing-current-state.md](14-testing-current-state.md) | Сведённое состояние тестов |
| [15-feature-matrix.md](15-feature-matrix.md) | Capabilities со стабильными ID |
| [16-risks.md](16-risks.md) | Обоснованные риски |
| [17-open-questions.md](17-open-questions.md) | Нерешённые вопросы |
| [18-verification-log.md](18-verification-log.md) | Только обращения к коду |
| [19-source-traceability.md](19-source-traceability.md) | Сопоставление источников, классификация, self-check |
| [20-freeze-record.md](20-freeze-record.md) | Server As-Is v1 — статус заморозки и закрытие review |

Происхождение аудитов определяется архивом и путём внутри него, а не устаревшими ссылками аудитов на каталоги проекта. Архивы содержат по 19 Markdown-файлов; упоминаемые ими client-handoff-файлы в предоставленных архивах отсутствуют и источниками этой спецификации не являются.
