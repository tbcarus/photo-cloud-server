# 04. Media Data Contract

> Все поля, пересекающие границу server ↔ client, с указанием владельца данных.
>
> **Ownership:**
> - `CLIENT_SUPPLIED` — клиент передаёт, сервер принимает как есть;
> - `SERVER_GENERATED` — сервер создаёт, клиент не может повлиять;
> - `SERVER_AUTHORITATIVE` — клиент может предложить значение, но сервер вычисляет своё и оно побеждает;
> - `CLIENT_LOCAL_ONLY` — существует только на клиенте, сервер об этом не знает.

---

## 1. `FileItemDto` — главный объект

Возвращается из: `POST /files`, `POST /files/upload`, `GET /files` (в `items[]`), `GET /files/{id}`, `PATCH /files/{id}`, `POST /files/{id}/move`, `POST /files/{id}/copy`.

```json
{
  "id": 42,
  "folderId": 7,
  "originalFilename": "photo.jpg",
  "mimeType": "image/jpeg",
  "size": 123456,
  "checksum": "3f8a...(64 hex lowercase)",
  "fileType": "IMAGE",
  "capturedAt": "2026-05-17T10:15:30",
  "uploadedAt": "2026-05-17T10:16:00",
  "deletedAt": null,
  "metadata": { ... }
}
```

| Поле | Type | Nullable | Ownership | Семантика |
| --- | --- | --- | --- | --- |
| `id` | `number` (int64) | ❌ | **SERVER_GENERATED** | Идентификатор логической записи файла. Уникален глобально. **Стабилен** при rename/move. При `copy` создаётся **новый** `id`. При повторной загрузке того же содержимого в ту же папку возвращается **прежний** `id` — это главный признак дубликата. |
| `folderId` | `number` (int64) | ❌ | **SERVER_AUTHORITATIVE** | Папка, в которой лежит файл. Клиент может задать при upload (`folderId`) — сервер проверит владение; если не задан, сервер выбирает `Camera` или `Files` по типу файла. Меняется через `move`. |
| `originalFilename` | `string` | ❌ | **SERVER_AUTHORITATIVE** | Отображаемое имя. Клиент предлагает его именем части multipart, но сервер **санитизирует** (заменяет `< > : " / \ | ? *` и control-символы на `_`) и **обрезает до 255**. ⚠️ При загрузке **дубликата** возвращается имя **ранее сохранённой** записи, а не присланное. Изменяется через `PATCH` (поле запроса — `originalName`). |
| `mimeType` | `string` | ❌ | **SERVER_AUTHORITATIVE** | Определяется Apache Tika **по содержимому файла**. `Content-Type` части multipart **полностью игнорируется**. Пример: файл `.bin` с JPEG-байтами получит `image/jpeg`. |
| `size` | `number` (int64) | ❌ | **SERVER_GENERATED** | Размер в байтах, посчитанный сервером при приёме потока. |
| `checksum` | `string` (64 hex, **lowercase**) | ❌ | **SERVER_GENERATED** | SHA-256 содержимого. **Клиент не может передать checksum при загрузке** — сервер всегда считает сам. Ключевое поле дедупликации. |
| `fileType` | `string` (enum) | ❌ | **SERVER_GENERATED** | `IMAGE` \| `VIDEO` \| `AUDIO` \| `DOCUMENT` \| `ARCHIVE` \| `OTHER`. Выводится из `mimeType`. Определяет выбор папки по умолчанию. |
| `capturedAt` | `string` (LocalDateTime) | ❌ | **SERVER_AUTHORITATIVE** | Дата съёмки из EXIF (`DateTimeOriginal`, иначе `DateTimeDigitized`). ⚠️ **Если EXIF нет — равен `uploadedAt`.** Для **всех видео** EXIF не читается, значит `capturedAt == uploadedAt` всегда. **Первичный ключ сортировки ленты.** |
| `uploadedAt` | `string` (LocalDateTime) | ❌ | **SERVER_GENERATED** | Момент сохранения на сервере (`LocalDateTime.now()` серверной JVM). Не меняется при rename/move. При `copy` — время копирования. |
| `deletedAt` | `string` \| `null` | ✅ | **SERVER_GENERATED** | ⚠️ **Всегда `null`.** Поле зарезервировано под корзину, но механизм не реализован: удаление — hard delete, запись просто исчезает. **Не строить на этом поле логику.** |
| `metadata` | `object` \| `null` | ✅ | **SERVER_GENERATED** | `null`, если не извлечено ни одного поля (все не-изображения; изображения без EXIF; повреждённые EXIF). |

---

## 2. `FileMetadataDto` — вложенный объект

```json
{
  "width": 4032, "height": 3024, "durationSec": null,
  "cameraMake": "Google", "cameraModel": "Pixel 7", "lensModel": null,
  "exposureTime": "1/120", "fNumber": 1.8, "iso": 100,
  "focalLength": 4.38, "latitude": 55.7558, "longitude": 37.6173
}
```

**Все поля nullable, все — `SERVER_GENERATED`.** Клиент не может передать метаданные при загрузке.

| Поле | Type | Источник | Примечания |
| --- | --- | --- | --- |
| `width` | `number` (int32) | JPEG/PNG directory | заполняется только для JPEG и PNG |
| `height` | `number` (int32) | там же | то же |
| `durationSec` | `number` (int32) | — | ⚠️ **всегда `null`** — сервер не обрабатывает видео |
| `cameraMake` | `string` | EXIF IFD0 `Make` | |
| `cameraModel` | `string` | EXIF IFD0 `Model` | |
| `lensModel` | `string` | EXIF SubIFD `LensModel` | часто `null` |
| `exposureTime` | `string` | EXIF SubIFD | **строка**, не число: `"1/120"` |
| `fNumber` | `number` (decimal) | EXIF SubIFD, rational | сериализуется под именем **`fNumber`** (заглавная N) |
| `iso` | `number` (int32) | EXIF SubIFD | |
| `focalLength` | `number` (decimal) | EXIF SubIFD, rational | в миллиметрах |
| `latitude` | `number` (decimal) | EXIF GPS | точность БД `NUMERIC(10,7)` |
| `longitude` | `number` (decimal) | EXIF GPS | то же |

**Важно для клиента:**
1. `metadata` целиком `null` — это норма, а не ошибка.
2. Отдельные поля внутри `metadata` тоже могут быть `null` независимо друг от друга.
3. **Метаданные извлекаются только из `image/*`.** Для видео, аудио, документов и архивов `metadata` всегда `null`.
4. Ориентация (EXIF Orientation) **не извлекается** — клиенту нужно определять её самому при отображении.
5. GPS-координаты передаются в открытом виде — учитывайте это в политике приватности.

---

## 3. `FolderDto`

```json
{"id": 10, "parentId": 1, "name": "Trips", "folderType": "USER",
 "createdAt": "2026-05-17T10:15:30", "updatedAt": "2026-05-17T10:15:30"}
```

| Поле | Type | Nullable | Ownership | Семантика |
| --- | --- | --- | --- | --- |
| `id` | `number` (int64) | ❌ | **SERVER_GENERATED** | Стабилен на всё время жизни папки. |
| `parentId` | `number` \| `null` | ✅ | **SERVER_AUTHORITATIVE** | `null` **только** у `ROOT`. Меняется через `move`. |
| `name` | `string` | ❌ | **SERVER_AUTHORITATIVE** | Клиент задаёт при создании; сервер делает `trim` и проверяет длину (≤255) и уникальность в родителе (case-insensitive). Системные папки имеют фиксированные имена: `"root"`, `"Camera"`, `"Files"`. |
| `folderType` | `string` (enum) | ❌ | **SERVER_GENERATED** | `ROOT` \| `CAMERA` \| `FILES` \| `USER`. Клиент через API может создать **только** `USER`. **Никогда не меняется.** |
| `createdAt` | `string` (LocalDateTime) | ❌ | **SERVER_GENERATED** | |
| `updatedAt` | `string` (LocalDateTime) | ❌ | **SERVER_GENERATED** | Обновляется при rename и move. |

**Семантика типов папок:**

| Тип | Кто создаёт | Rename | Move | Delete | Особенности |
| --- | --- | --- | --- | --- | --- |
| `ROOT` | сервер (лениво) | ❌ | ❌ | ❌ | ровно одна на пользователя; `parentId = null` |
| `CAMERA` | сервер (лениво) | ❌ | ❌ | ❌ | папка по умолчанию для `IMAGE`/`VIDEO`; **допускает одинаковые имена файлов** |
| `FILES` | сервер (лениво) | ❌ | ❌ | ❌ | папка по умолчанию для остальных типов |
| `USER` | клиент | ✅ | ✅ | ✅ только пустую | нельзя создавать внутри `CAMERA`/`FILES` |

---

## 4. `FileChecksumDto`

Из `GET /api/v1/files/checksums`:

```json
[{"id": 42, "originalFilename": "photo.jpg", "checksum": "3f8a..."}]
```

| Поле | Type | Ownership | Примечание |
| --- | --- | --- | --- |
| `id` | `number` | SERVER_GENERATED | тот же `id`, что в `FileItemDto` |
| `originalFilename` | `string` | SERVER_AUTHORITATIVE | |
| `checksum` | `string` | SERVER_GENERATED | |

⚠️ **Без пагинации и без фильтра по папке** — возвращаются все файлы пользователя. Поле `folderId` **отсутствует**, поэтому по этому ответу нельзя понять, в какой папке лежит файл. Для синхронизации используйте `POST /files/checksums/exists`.

---

## 5. `ChecksumExistsResponse`

```json
{"existing": ["aaaa..."], "missing": ["bbbb..."]}
```

| Поле | Type | Ownership | Семантика |
| --- | --- | --- | --- |
| `existing` | `string[]` | SERVER_AUTHORITATIVE | checksum'ы, для которых **в указанной папке** уже есть файл |
| `missing` | `string[]` | SERVER_AUTHORITATIVE | остальные — их нужно загружать |

**Гарантии:** значения всегда в **lowercase**; порядок соответствует порядку первого появления во входном списке; дубликаты входа схлопнуты; объединение `existing ∪ missing` = уникальные значения входа.

**Чего в ответе НЕТ:** `id` найденного файла, его имени, размера, `fileType`, `capturedAt`. Ответ намеренно минимален — «загружать или нет».

---

## 6. `UserDto`

```json
{"id": 1, "email": "user@example.com", "displayName": null,
 "enabled": true, "banned": false, "roles": ["USER"],
 "createdAt": "...", "lastUpdate": "...", "lastLoginAt": "..."}
```

| Поле | Type | Nullable | Ownership | Примечание |
| --- | --- | --- | --- | --- |
| `id` | `number` | ❌ | SERVER_GENERATED | в API нигде не требуется — все запросы идут от текущего пользователя |
| `email` | `string` | ❌ | SERVER_AUTHORITATIVE | всегда lowercase; менять нельзя |
| `displayName` | `string` \| `null` | ✅ | SERVER_GENERATED | **всегда `null` для новых аккаунтов** — при регистрации не задаётся, изменить негде (`PATCH /profile` = `501`) |
| `enabled` | `boolean` | ❌ | SERVER_GENERATED | при `false` войти нельзя (но если токен уже получен, он продолжит работать) |
| `banned` | `boolean` | ❌ | SERVER_GENERATED | механизма установки в API нет |
| `roles` | `string[]` | ❌ | SERVER_GENERATED | всегда `["USER"]`; **сервер роли нигде не проверяет** |
| `createdAt` | `string` | ❌ | SERVER_GENERATED | |
| `lastUpdate` | `string` | ❌ | SERVER_GENERATED | |
| `lastLoginAt` | `string` \| `null` | ✅ | SERVER_GENERATED | `null` до первого входа |

---

## 7. `PageResponse<T>`

```json
{"items": [...], "page": 0, "size": 10,
 "totalElements": 125, "totalPages": 13, "hasNext": true, "hasPrevious": false}
```

| Поле | Type | Примечание |
| --- | --- | --- |
| `items` | `T[]` | `FileItemDto[]` |
| `page` | `number` (int32) | 0-based |
| `size` | `number` (int32) | эхо запрошенного значения |
| `totalElements` | `number` (int64) | всего записей |
| `totalPages` | `number` (int32) | всего страниц |
| `hasNext` | `boolean` | |
| `hasPrevious` | `boolean` | |

**Ограничения:** offset-based пагинация без курсора — при изменении данных между запросами страницы могут «поехать» (файл смещается или дублируется в выдаче). Верхняя граница `size` на сервере не задана.

---

## 8. Форматы значений

| Тип | Формат | Пример | Примечание |
| --- | --- | --- | --- |
| Дата и время | ISO-8601 **без таймзоны и смещения** | `2026-05-17T10:15:30` | ⚠️ Это `LocalDateTime` серверной JVM. **Часовой пояс сервера клиенту неизвестен.** При отображении интерпретируйте согласованно (например, как локальное время сервера), а при сравнении не смешивайте с локальным временем устройства. |
| Дата с миллисекундами | могут присутствовать | `2026-05-17T10:15:30.123` | Jackson опускает нулевые доли секунды — длина строки **не фиксирована** |
| Checksum | 64 hex, **lowercase** | `3f8a1b...` | на входе `checksums/exists` допускается любой регистр, ответ всегда lowercase |
| `id` | `int64` | `42` | в Kotlin — `Long` |
| `size` | `int64` (байты) | `123456` | `Long` |
| Decimal | JSON number | `1.8`, `4.38` | сервер применяет `stripTrailingZeros()` перед сохранением; в Kotlin используйте `BigDecimal` или `Double` |
| Enum | строка в верхнем регистре | `"IMAGE"`, `"USER"` | ⚠️ **предусмотрите неизвестные значения** — сервер может добавить новые |

---

## 9. Данные, которые сервер НЕ раскрывает

**Полностью скрыто (внутреннее состояние сервера):**

| Скрытое | Почему важно знать |
| --- | --- |
| `filePath`, `filename`, `fileExtension` физического объекта | клиент **не может** обратиться к файлу иначе как через `GET /files/{id}/download` |
| `storedObjectId` | связь «логическая запись → физический объект» невидима |
| `storage.root` и структура каталогов | |
| Факт, что два `FileItem` ссылаются на один физический объект | при текущей модели такого и не бывает |
| Хеш пароля | |
| Внутренние id refresh-токенов | |

**Практическое следствие:** клиент не может по ответу API узнать, сколько места файл занимает на сервере физически, и есть ли у него копии в других папках. Единственный способ найти «такой же файл» — сравнивать `checksum` из `GET /files`.

---

## 10. `CLIENT_LOCAL_ONLY` — чего сервер не знает

Эти данные существуют только на устройстве; сервер о них не осведомлён и хранить их не умеет:

| Данные | Комментарий |
| --- | --- |
| Локальный путь файла на устройстве | не передаётся при загрузке |
| MediaStore ID / Content URI | |
| Статус синхронизации (`НОВЫЙ`, `НА ПРОВЕРКЕ`, `НА ЗАГРУЗКУ`, `ЗАГРУЖЕН`) | описан в `README-description.md` как **клиентская** модель |
| Дата добавления файла на устройство | |
| Признак «файл удалён на устройстве» | сервер об этом не узнает |
| Идентификатор устройства | модели `Device` нет |
| Очередь загрузок, счётчики попыток | |
| Локальные альбомы/папки галереи | сервер знает только целевую папку |
| Настройки автозагрузки (Wi-Fi only, интервал) | `/profile/settings` возвращает `501` |
| Кэш миниатюр | сервер миниатюры не отдаёт |

> **Ключевой вывод для проектирования клиента:** сервер **не является источником истины для состояния синхронизации**. Он отвечает только на вопрос «есть ли файл с таким checksum в такой папке». Всё остальное клиент обязан хранить и вычислять сам.

---

## 11. Отображение серверных полей на локальную модель

**[Оценочное — рекомендация, а не описание сервера]**

| Серверное поле | Хранить локально? | Зачем |
| --- | --- | --- |
| `id` | ✅ **обязательно** | единственный способ адресовать файл в API (download, rename, move, delete) |
| `checksum` | ✅ **обязательно** | ключ сопоставления с локальным файлом и вход для `checksums/exists` |
| `folderId` | ✅ обязательно | нужен для `checksums/exists`; определяет, где лежит файл |
| `capturedAt`, `uploadedAt` | ✅ | для сортировки, совпадающей с серверной |
| `originalFilename`, `size`, `mimeType`, `fileType` | ✅ | отображение без дополнительных запросов |
| `metadata` | ⚠️ по необходимости | объёмно; для карты и EXIF-панели |
| `deletedAt` | ❌ | всегда `null` |
| `roles`, `enabled`, `banned` | ❌ | сервер их не использует для авторизации |

**Правило соответствия:** локальный файл считается загруженным, если существует серверная запись с **тем же `checksum` в целевой `folderId`**. Сопоставление по имени файла ненадёжно: в `CAMERA` имена могут повторяться, а `originalFilename` изменяется через rename.
