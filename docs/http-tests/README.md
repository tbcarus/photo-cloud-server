# HTTP Smoke-тесты

Эта папка содержит ручные smoke-запросы для текущего серверного API-контракта `/api/v1`.

## Файлы

- `api-smoke-tests.http` - запросы IntelliJ HTTP Client для всех endpoint'ов, которые сейчас объявлены в контроллерах.
- `api-curl-examples.md` - аналогичные `curl`-примеры для быстрой проверки через терминал или Postman и для будущей миграции Android-клиента.

## Как запускать `.http`-тесты в IntelliJ

1. Запусти сервер локально, обычно на `http://localhost:8080`.
2. Открой `api-smoke-tests.http`.
3. Проверь переменные в начале файла: `@baseUrl`, `@email`, `@password`, `@confirmationCode`, `@passwordResetCode`, `@mediaFileId` и `@uploadFile`.
4. Сначала выполни запрос login. Он сохранит `accessToken` и `refreshToken` в глобальные переменные IntelliJ.
5. После login запускай запросы, которым нужна авторизация.

Пример multipart upload ожидает путь к локальному файлу в `@uploadFile`. Перед запуском замени его на существующий файл изображения или видео.

## Как использовать curl-примеры

1. Создай переменные Postman `baseUrl`, `accessToken` и `refreshToken`.
2. Скопируй нужную команду из `api-curl-examples.md`.
3. Замени значения-заглушки вроде `<CONFIRMATION_CODE>`, `<PASSWORD_RESET_CODE>` и id медиафайлов.

## Требования к JWT

Публичные endpoint'ы:

- `GET /api/v1/test`
- `POST /api/v1/auth/register`
- `GET /api/v1/auth/register/confirm?code=...`
- `POST /api/v1/auth/register/resend`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/password/reset/request`
- `POST /api/v1/auth/password/reset/confirm`
- `POST /api/v1/auth/password/reset/resend`
- `GET /api/v1/auth/password/reset/page?code=...`
- `POST /api/v1/auth/password/reset/page`

Endpoint'ы, которым нужен заголовок `Authorization: Bearer <ACCESS_TOKEN>`:

- `GET /api/v1/test/auth`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/logout-all`
- `POST /api/v1/auth/logout-others`
- все endpoint'ы `/api/v1/profile...`
- все endpoint'ы `/api/v1/media...`

## TODO / НЕ РЕАЛИЗОВАНО

Эти endpoint'ы уже есть в контроллерах как smoke-проверяемые заглушки и сейчас возвращают `501 NOT_IMPLEMENTED`:

- `POST /api/v1/auth/register/resend`
- `POST /api/v1/auth/password/reset/resend`
- `GET /api/v1/auth/password/reset/page?code=...`
- `POST /api/v1/auth/password/reset/page`
- `PATCH /api/v1/profile`
- `GET /api/v1/profile/settings`
- `PATCH /api/v1/profile/settings`
- `PATCH /api/v1/media/{id}`
- `GET /api/v1/media/{id}/thumbnail`
- `POST /api/v1/media/check-exist`
- `POST /api/v1/media/checksums/exists`

## Заметки по текущей реализации

- Контроллер списка медиафайлов сейчас читает только `page` и `size`; `type`, `from` и `to` добавлены в примеры как будущие параметры фильтрации.
- Подтверждение регистрации сейчас принимает только `code`; старый URL `/register/ACTIVATE?email=...&code=...` больше не входит в API-контракт.
