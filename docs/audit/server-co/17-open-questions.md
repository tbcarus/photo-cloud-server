# Вопросы, которые код не разрешает

Здесь нет предполагаемых ответов. После каждого вопроса указано, почему он влияет на трактовку As-Is. Факты перед вопросами отмечены отдельно; отсутствие требований не трактуется как баг.

## Deployment и данные

1. Какая версия/commit реально развёрнута и какие changesets есть в DATABASECHANGELOG? [CONFIRMED] В checkout верхний SQL14, live DB не читалась.
2. Применялась ли destructive migration10 к непустой media_file, существует ли архив старых metadata/bytes? [CONFIRMED] Скрипт не переносит данные.
3. Есть ли duplicate groups до migration14, и какое продуктовое решение принято по ним? [CONFIRMED] Есть ручной SELECT-аудит, автополитики удаления нет.
4. Где production storage.root/temp-dir, один ли volume/filesystem, есть ли symlink/junction и external cleanup? [CONFIRMED] Конфигурация допускает overrides.
5. Один backend instance или несколько, общий ли mount, как совместно восстанавливаются БД и байты? Эти сведения не заданы deployment-файлами.
6. Какие actual PostgreSQL version, locale/collation/timezone, JVM timezone, proxy/TLS/forwarded headers? Test postgres16 не доказывает production version.
7. Кто читает логи, каков retention и были ли реальные секреты в smoke-примерах? Значения не проверялись на валидность.

## Auth и аккаунты

8. Должны ли ban/disable немедленно прекращать refresh и доступ по уже выданному access? [CONFIRMED] Сейчас только login проверяет эти флаги.
9. Должна ли смена пароля отзывать все сессии? [CONFIRMED] Сейчас не отзывает, smoke-комментарий предполагает проверку противоположного.
10. Что является «текущим устройством» в logout-others, если клиент прислал чужой собственный/просроченный refresh? [CONFIRMED] Device ID и связь пары access↔refresh не сохранены.
11. Как пользователь должен повторно получить activation письмо после неуспешной доставки? [CONFIRMED] Resend501, повтор register409.
12. Какой UI должен завершать reset link, пока page501? Есть ли внешний frontend/deep link, отсутствующий в репозитории?
13. Каковы требуемые парольная политика, ограничения попыток и policy срока email code? [DOCUMENTED] README-description говорит о 1 дне reset, код —3 дня; helper лимита не включён.
14. Существуют ли внешние admin operations для banned/roles/delete account? Backend API не содержит их.

## Файлы и синхронизация

15. Является ли folder-scoped checksum окончательной продуктовой семантикой дубля? [CONFIRMED] Код/SQL14 именно так работают; глобальная уникальность не реализована.
16. Что считать успешной backup-проверкой, если exists=existing, но download404 или содержимое изменилось на диске? [CONFIRMED] Pre-check не проверяет bytes.
17. Нужно ли сохранять имя/дату повторного upload в уже существующую запись? [CONFIRMED] Сейчас старый DTO возвращается неизменённым.
18. Как Android должен получить Camera folderId до первого default upload, если root children пуст? [CONFIRMED] GET root создаёт только ROOT; dedicated ensure-camera API нет.
19. Как связывается несколько локальных файлов с одним server FileItem при identical content в одной папке? Сервер не хранит client media ID.
20. Что должно происходить при удалении или переносе файла на телефоне и отдельно на сервере? Change feed/tombstone/device sync absent.
21. Что делать с network timeout после copy/confirm/delete, когда HTTP status повтора отличается от первого? Operation journal отсутствует.
22. Как трактовать capture time и EXIF timezone для сортировки клиента? Wire LocalDateTime без offset; fallback upload.
23. Какие MIME/форматы видео/фото обязательны для metadata, preview и download-range? General byte storage реализовано шире extractor.
24. Разрешены ли документы в Camera и произвольные типы в пользовательских папках как продуктовое поведение? [CONFIRMED] Explicit folderId не ограничивает type.
25. Какие пользовательские ограничения размера/числа файлов/дисковой квоты предполагаются? [CONFIRMED] Есть только file/request limit, quota model отсутствует.
26. Должен ли delete204 гарантировать physical erasure? [CONFIRMED] Сейчас DB удалена, disk IOException подавляется.

## API / ownership / история

27. Какие endpoints реально вызывает текущий Android, включая legacy POST /files и GET /files/checksums? Серверные tests не являются usage telemetry.
28. Какие contract/status decisions требуются для checksum conflict move/copy race: 409 либо другое? [CONFIRMED] Сейчас часть этих ошибок500.
29. Есть ли externally seeded shared StoredObject references и как должны действовать права удаления владельца? Current API sharing не создаёт.
30. Нужно ли сохранять deletedAt/version/history и как долго? [CONFIRMED] Текущий delete hard, поле reserved.
31. Допускается ли пользовательское переименование extension независимо от content MIME? [CONFIRMED] Rename только logical name; copy может иметь extension mismatch.
32. Какой из существующих документов считается клиентской спецификацией для релиза? Единого api-contract.md нет; четыре контракта ближе к коду, smoke и README содержат расхождения.

