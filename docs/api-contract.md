# API contract index

Этот файл больше не является полным API-контрактом. Актуальный контракт разделен по областям, чтобы не дублировать одно и то же описание в нескольких местах.

Источник правды: текущий код в `src/main/java`.

## Актуальные документы

| Документ | Что описывает |
| --- | --- |
| `docs/api-user-contract.md` | auth/register/password/profile/root-test endpoint-ы, DTO, security и ошибки |
| `docs/api-folder-contract.md` | Folder API, типы папок и инварианты логической структуры |
| `docs/api-file-contract.md` | File API, upload/download/copy/move/rename/delete и модель `FileItem`/`StoredObject` |
| `docs/api-checksum-sync-contract.md` | `POST /api/v1/files/checksums/exists` как checksum pre-check перед upload |
| `docs/application-overview.md` | обзор доменной модели и архитектурных решений приложения |

## Что изменилось

Раньше этот файл содержал большой смешанный контракт и начал расходиться с реализацией после появления Folder API, новых File API операций и checksum exists endpoint-а. Чтобы не поддерживать две версии одного контракта, подробное описание вынесено в документы выше.

## Не изменять без отдельной задачи

`docs/api-endpoint-migration.md` остается отдельной миграционной заметкой и не является текущим API-контрактом.
