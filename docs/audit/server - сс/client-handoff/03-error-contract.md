# 03. Error Contract

> Фактическое поведение сервера при ошибках. Всё восстановлено по коду `GlobalExceptionHandler`, `JsonAuthenticationEntryPoint`, `JsonAccessDeniedHandler` и по сервисному слою.

---

## 1. Три формата ответа об ошибке

> ⚠️ **Клиент должен быть готов к трём разным форматам.** Единого контракта ошибок на сервере нет.

### Формат A — основной (`ErrorResponse`)

Используется всеми обработанными исключениями, а также ответами `401` и `403`:

```json
{
  "id": "2e7f5f91-9a9f-4e5d-b5b9-1fb1c72df9ea",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "fieldErrors": { "email": "Email must be valid" }
}
```

| Поле | Тип | Гарантии |
| --- | --- | --- |
| `id` | string (UUID) | всегда присутствует; **на сервере нигде не логируется** — для обращения в поддержку бесполезен |
| `code` | string (enum) | всегда присутствует; **основное поле для машинной обработки** |
| `message` | string | всегда присутствует; язык **смешанный** (русский/английский) — не показывать пользователю как есть |
| `fieldErrors` | object \| null | присутствует всегда; заполняется **только** при `code = VALIDATION_ERROR` |

### Формат B — заглушки `501`

7 нереализованных endpoint'ов возвращают **не** `ErrorResponse`:

```json
{"message": "Profile update is not implemented yet"}
```

Полей `id`, `code`, `fieldErrors` **нет**.

### Формат C — необработанные исключения

Если на сервере возникло исключение, для которого нет обработчика (`NullPointerException`, `NoSuchElementException`, `IOException`, сбой БД), возвращается **стандартный формат Spring Boot**:

```json
{
  "timestamp": "2026-05-17T10:15:30.123+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/api/v1/files"
}
```

Полей `code` и `id` **нет**.

> **Практический вывод для клиента:** парсер ошибок должен пробовать формат A, при отсутствии `code` — падать на формат C/B и трактовать ответ по HTTP-статусу. Наличие поля `code` — надёжный признак «сервер распознал ситуацию».

---

## 2. Полный перечень `code`

| `code` | HTTP | Когда возникает |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | нарушена Bean Validation; заполнен `fieldErrors` |
| `BAD_REQUEST` | 400 | доменное ограничение, malformed body, отсутствующий query-параметр |
| `BAD_REGISTRATION_REQUEST` | 400 | email-код неверен / использован / истёк / не того типа |
| `UNAUTHORIZED` | 401 | нет/невалиден/истёк access token |
| `INVALID_CREDENTIALS` | 401 | login: неверные данные или неактивный аккаунт |
| `INVALID_REFRESH_TOKEN` | 401 | refresh token не найден / истёк / повреждён |
| `FORBIDDEN` | 403 | AccessDenied (на практике почти недостижим) |
| `REFRESH_TOKEN_REVOKED` | 403 | refresh token отозван (был logout) |
| `REFRESH_TOKEN_OWNERSHIP_ERROR` | 403 | refresh token принадлежит другому пользователю |
| `NOT_FOUND` | 404 | папка не найдена / не принадлежит пользователю |
| `REFRESH_TOKEN_NOT_FOUND` | 404 | refresh token отсутствует в БД (logout) |
| `FILE_ITEM_NOT_FOUND` | 404 | файл не найден / чужой / физический файл отсутствует |
| `CONFLICT` | 409 | email занят; конфликт имени файла/папки; конфликт checksum при copy |
| `FILE_TOO_LARGE` | 413 | превышен лимит размера файла или multipart-запроса |
| `DATABASE_CONSTRAINT_VIOLATION` | **500** | нарушение ограничения БД — **конфликт данных, не сбой сервера** |
| `INTERNAL_ERROR` | — | объявлен в enum, но **никогда не возвращается** |

---

## 3. Главная таблица: Operation → Status → Body → Meaning → Retryable

| Operation | HTTP status | Error body (`code`) | Meaning | Retryable |
| --- | --- | --- | --- | --- |
| **register** | 400 | `VALIDATION_ERROR` | неверный email или длина пароля не 4–20 | ❌ |
| register | 409 | `CONFLICT` | email уже зарегистрирован | ❌ |
| **register/confirm** | 400 | `BAD_REGISTRATION_REQUEST` | код неверен / использован / истёк | ❌ (нового кода получить нельзя — resend = 501) |
| **login** | 400 | `VALIDATION_ERROR` | пустые поля или пароль длиннее 20 | ❌ |
| login | 401 | `INVALID_CREDENTIALS` | неверные данные **или** аккаунт не подтверждён **или** заблокирован — неразличимо | ❌ |
| **refresh-token** | 400 | `VALIDATION_ERROR` | пустой `refreshToken` | ❌ |
| refresh-token | 401 | `INVALID_REFRESH_TOKEN` | токен неизвестен / истёк / повреждён | ❌ → **re-login** |
| refresh-token | 403 | `REFRESH_TOKEN_REVOKED` | был выполнен logout | ❌ → **re-login** |
| **logout / logout-others** | 400 | `VALIDATION_ERROR` | пустой `refreshToken` | ❌ |
| logout / logout-others | 401 | `UNAUTHORIZED` | **access token истёк** | ✅ **после refresh** |
| logout / logout-others | 403 | `REFRESH_TOKEN_OWNERSHIP_ERROR` | чужой токен | ❌ → очистить хранилище |
| logout / logout-others | 404 | `REFRESH_TOKEN_NOT_FOUND` | токена нет в БД | ❌ → **считать успехом** |
| **password/reset/request** | 400 | `VALIDATION_ERROR` | пустой email | ❌ |
| password/reset/request | 400 | `BAD_REQUEST` | «User \<email\> not found» | ❌ |
| **password/reset/confirm** | 400 | `VALIDATION_ERROR` | пустой пароль/код или длина не 4–20 | ❌ |
| password/reset/confirm | 400 | `BAD_REGISTRATION_REQUEST` | код неверен / использован / истёк | ❌ |
| **любой защищённый** | 401 | `UNAUTHORIZED` | нет/истёк/невалиден access token | ✅ **после refresh, повторить 1 раз** |
| **folders (create)** | 400 | `BAD_REQUEST` | пустое/длинное имя; попытка создать внутри `CAMERA`/`FILES` | ❌ |
| folders (create) | 404 | `NOT_FOUND` | `parentId` не существует или чужой | ❌ → пересинхронизировать дерево |
| folders (create) | 409 | `CONFLICT` | имя занято (без учёта регистра) или зарезервировано (`Camera`/`Files` в ROOT) | ⚠️ только с другим именем |
| **folders (rename/move)** | 400 | `BAD_REQUEST` | системная папка; target = сама папка; target — потомок; target = `CAMERA`/`FILES` | ❌ |
| folders (rename/move) | 404 | `NOT_FOUND` | папка или target не найдены/чужие | ❌ |
| folders (rename/move) | 409 | `CONFLICT` | имя занято в целевом родителе | ⚠️ только с другим именем |
| **folders (delete)** | 400 | `BAD_REQUEST` | системная папка; есть подпапки; есть файлы | ❌ → сначала очистить папку |
| folders (delete) | 404 | `NOT_FOUND` | папка не найдена/чужая | ❌ → **считать успехом** |
| **upload** | 400 | `BAD_REQUEST` | «Файл пустой» (0 байт) | ❌ |
| upload | 400 | `BAD_REQUEST` | «Required request parameter is missing» — нет части `file` | ❌ (ошибка клиента) |
| upload | 404 | `NOT_FOUND` | `folderId` не существует или чужой | ❌ → пересинхронизировать дерево |
| upload | 409 | `CONFLICT` | «File with this name already exists in folder» (вне `CAMERA`) | ⚠️ **только с другим именем файла** |
| upload | 413 | `FILE_TOO_LARGE` | файл > 100 MiB или multipart > 110 MB | ❌ **никогда** |
| upload | таймаут / нет ответа | — | обрыв сети | ✅ **безопасно — upload идемпотентен в пределах папки** |
| **list files** | 404 | `NOT_FOUND` | `folderId` не существует или чужой | ❌ |
| **get file** | 404 | `FILE_ITEM_NOT_FOUND` | файл не найден или чужой | ❌ → удалить из локального индекса |
| **download** | 404 | `FILE_ITEM_NOT_FOUND` | файл не найден, чужой **или физически отсутствует на сервере** | ❌ → пометить локально как недоступный |
| download | обрыв соединения | — | сеть | ✅ **но только с нуля** — Range не поддерживается |
| **rename file** | 400 | `BAD_REQUEST` / `VALIDATION_ERROR` | пустое имя после trim; длина >255 | ❌ |
| rename file | 404 | `FILE_ITEM_NOT_FOUND` | файл не найден/чужой | ❌ |
| rename file | 409 | `CONFLICT` | имя занято в папке (вне `CAMERA`) | ⚠️ только с другим именем |
| **move file** | 400 | `VALIDATION_ERROR` | нет `targetFolderId` | ❌ |
| move file | 404 | `FILE_ITEM_NOT_FOUND` / `NOT_FOUND` | файл или целевая папка не найдены/чужие | ❌ |
| move file | 409 | `CONFLICT` | конфликт **имени** в целевой папке | ⚠️ переименовать и повторить |
| move file | **500** | `DATABASE_CONSTRAINT_VIOLATION` | в целевой папке уже есть файл с **тем же checksum** | ❌ **НИКОГДА не повторять** |
| **copy file** | 404 | `FILE_ITEM_NOT_FOUND` / `NOT_FOUND` | источник или целевая папка не найдены | ❌ |
| copy file | 409 | `CONFLICT` | конфликт имени **или** конфликт checksum в целевой папке | ❌ (копия уже есть) |
| **delete file** | 404 | `FILE_ITEM_NOT_FOUND` | файл не найден/чужой | ❌ → **считать успехом** |
| **checksums/exists** | 400 | `VALIDATION_ERROR` | нет `folderId`; пустой массив; элемент не 64 hex | ❌ |
| checksums/exists | 400 | `BAD_REQUEST` | «Checksum batch size must be at most 500» | ⚠️ **разбить на батчи ≤500 и повторить** |
| checksums/exists | 404 | `NOT_FOUND` | папка не существует или чужая | ❌ |
| **любой** | 500 | без поля `code` (формат C) | необработанное исключение на сервере | ✅ **exponential backoff** — может быть временным |
| **любой** | 501 | формат B (`{"message":...}`) | endpoint не реализован | ❌ никогда |
| **любой** | 503 / нет ответа | — | сервер недоступен | ✅ backoff |

---

## 4. Три правила, которые легко нарушить

### Правило 1: `500` — не всегда «повторить позже»

Стандартная политика «retry на 5xx» здесь **неверна**. Различайте:

```
если status == 500:
    если body.code == "DATABASE_CONSTRAINT_VIOLATION":
        → это КОНФЛИКТ ДАННЫХ, retry бесполезен всегда
        → показать пользователю, что операция невозможна
    иначе (поля code нет — формат C):
        → возможно, временный сбой (БД, диск, сеть)
        → exponential backoff, ограниченное число попыток
```

Основной практический случай — `move` файла в папку, где уже есть файл с тем же содержимым.

### Правило 2: `404` на удаляющих операциях — это успех

`DELETE /files/{id}`, `DELETE /folders/{id}` и `POST /auth/logout` не идемпотентны: повтор после успешного выполнения вернёт `404`. Если клиент повторяет запрос из-за таймаута, `404` означает «уже удалено» — обрабатывайте как успех.

### Правило 3: `401` требует ровно одной попытки refresh

```
401 → refresh → успех? → повторить исходный запрос ОДИН раз
                       → снова 401? → это НЕ проблема токена, не зацикливаться
      refresh → 401/403 → полный re-login
```

---

## 5. Ошибки валидации: структура `fieldErrors`

Заполняется только при `code = VALIDATION_ERROR`. Ключ — имя поля, значение — сообщение.

**Тело запроса:**
```json
{"id":"...","code":"VALIDATION_ERROR","message":"Validation failed",
 "fieldErrors":{"email":"Email must be valid","password":"Password length must be from 4 to 20"}}
```

**Элементы массива — с индексом:**
```json
{"fieldErrors":{"checksums[0]":"must match \"^[0-9a-fA-F]{64}$\""}}
```

**Query-параметры** (например, `email` в reset/request) — ключом становится последний сегмент пути свойства: `"email"`.

**Известные сообщения валидации:**

| Поле | Сообщение |
| --- | --- |
| `email` | `Email must not be blank` / `Email must be valid` |
| `password` | `Password must not be blank` / `Password length must be from 4 to 20` |
| `refreshToken` | `Refresh token must not be blank` |
| `code` | `Code must not be blank` |
| `name`, `originalName` | стандартные сообщения Bean Validation (`must not be blank`, `size must be between 0 and 255`) |
| `folderId`, `targetFolderId`, `targetParentId` | `must not be null` |
| `checksums` | `must not be empty` |
| `checksums[i]` | `must match "^[0-9a-fA-F]{64}$"` |

**Ограничение:** `fieldErrors` собирается в `HashMap` — **порядок полей не гарантирован**, и при нескольких ошибках на одном поле останется только одна.

---

## 6. Ошибки, которые сервер НЕ возвращает

Ситуации, где ошибки можно было бы ожидать, но сервер отвечает успехом:

| Ситуация | Ответ сервера | Что это значит для клиента |
| --- | --- | --- |
| Загрузка дубликата в ту же папку | **`200`** + существующий `FileItemDto` | не ошибка; **признак дубля — что `id` уже известен клиенту** |
| Не удалось отправить письмо при регистрации | `201` | клиент не узнает; нужно предупреждать «если письмо не пришло…» |
| Не удалось отправить письмо при сбросе пароля | `200` | то же |
| Не удалось удалить физический файл при `DELETE` | `204` | запись удалена, файл остался на сервере — клиенту всё равно |
| Не удалось извлечь EXIF | `200`, `metadata: null` | не ошибка |
| Запрошен файл, физически отсутствующий на диске | `GET /files/{id}` → **`200`** | **обнаружится только при download (`404`)** |
| Забаненный пользователь с действующим токеном | все запросы работают | бан вступит в силу только после истечения refresh |

---

## 7. Рекомендуемый алгоритм обработки (сводка)

**[Оценочное — вывод из контракта, а не описание сервера]**

```
ответ = выполнить запрос

если сеть/таймаут:
    upload → безопасно повторить (идемпотентен в пределах папки)
    GET / refresh / checksums-exists → безопасно повторить
    DELETE / logout → повторить; 404 трактовать как успех
    login / register / copy / confirm → НЕ повторять автоматически

иначе по статусу:
    2xx → успех
    400 → ошибка клиента: показать fieldErrors или message; не повторять
    401 → refresh → повтор один раз → иначе re-login
    403 → REFRESH_TOKEN_* → re-login; FORBIDDEN → не повторять
    404 → на DELETE/logout: успех
          на остальных: ресурс пропал → удалить из локального индекса,
                        при NOT_FOUND по папке — пересинхронизировать дерево
    409 → конфликт: имя → предложить переименование
                    checksum (copy) → копия уже существует, не повторять
    413 → файл слишком большой: исключить из очереди навсегда
    500 + code=DATABASE_CONSTRAINT_VIOLATION → конфликт, НЕ повторять
    500 без code → backoff, ограниченное число попыток
    501 → функция не реализована: отключить в UI
```
