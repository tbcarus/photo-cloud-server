# Примеры curl-запросов API

## Переменные Postman

baseUrl = http://localhost:8080
accessToken = <ACCESS_TOKEN>
refreshToken = <REFRESH_TOKEN>

## RootController

### Публичная проверка подключения
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/test'
```

### Проверка подключения с авторизацией
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/test/auth' \
  --header 'Authorization: Bearer {{accessToken}}'
```

## RegisterController

### Регистрация пользователя
Статус: РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "email": "smoke-user@example.com",
  "password": "password123"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/auth/register' \
  --header 'Content-Type: application/json' \
  --data '{"email":"smoke-user@example.com","password":"password123"}'
```

### Подтверждение регистрации
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/auth/register/confirm?code=<CONFIRMATION_CODE>'
```

### Повторная отправка подтверждения регистрации
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/register/resend'
```

## AuthController

### Логин
Статус: РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "email": "smoke-user@example.com",
  "password": "password123"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data '{"email":"smoke-user@example.com","password":"password123"}'
```

### Обновление access token
Статус: РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "refreshToken": "<REFRESH_TOKEN>"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/auth/refresh-token' \
  --header 'Content-Type: application/json' \
  --data '{"refreshToken":"{{refreshToken}}"}'
```

### Logout текущей сессии/устройства
Статус: РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "refreshToken": "<REFRESH_TOKEN>"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/auth/logout' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"refreshToken":"{{refreshToken}}"}'
```

### Logout всех сессий
Статус: РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/logout-all' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Logout всех сессий, кроме текущей
Статус: РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "refreshToken": "<REFRESH_TOKEN>"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/auth/logout-others' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"refreshToken":"{{refreshToken}}"}'
```

## PasswordController

### Запрос сброса пароля
Статус: РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/password/reset/request?email=smoke-user@example.com'
```

### Подтверждение сброса пароля
Статус: РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/password/reset/confirm?password=password123&code=<PASSWORD_RESET_CODE>'
```

### Повторная отправка ссылки сброса пароля
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/password/reset/resend'
```

### HTML-страница сброса пароля
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/auth/password/reset/page?code=<PASSWORD_RESET_CODE>'
```

### Отправка формы HTML-страницы сброса пароля
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location --request POST '{{baseUrl}}/api/v1/auth/password/reset/page'
```

## UserController

### Получение профиля текущего пользователя
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/profile' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Обновление профиля текущего пользователя
Статус: TODO / НЕ РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "firstName": "Smoke",
  "lastName": "Tester"
}
```

```bash
curl --location --request PATCH '{{baseUrl}}/api/v1/profile' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"firstName":"Smoke","lastName":"Tester"}'
```

### Получение настроек профиля
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/profile/settings' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Обновление настроек профиля
Статус: TODO / НЕ РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "autoUploadEnabled": true
}
```

```bash
curl --location --request PATCH '{{baseUrl}}/api/v1/profile/settings' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"autoUploadEnabled":true}'
```

## MediaFileController

### Загрузка media-файла
Статус: РЕАЛИЗОВАНО

Имя multipart-поля: `file`.

```bash
curl --location '{{baseUrl}}/api/v1/media' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --form 'file=@"/absolute/path/to/sample-media.jpg";type=image/jpeg'
```

### Список media-файлов
Статус: РЕАЛИЗОВАНО

Текущий контроллер принимает `page` и `size`. `type`, `from` и `to` добавлены в пример как будущие параметры фильтрации и могут игнорироваться сервером до реализации фильтров.

```bash
curl --location '{{baseUrl}}/api/v1/media?page=0&size=10&type=IMAGE&from=2026-01-01T00:00:00&to=2026-12-31T23:59:59' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Получение метаданных media-файла
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/media/1' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Обновление метаданных media-файла
Статус: TODO / НЕ РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "originalFilename": "renamed-sample-media.jpg"
}
```

```bash
curl --location --request PATCH '{{baseUrl}}/api/v1/media/1' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"originalFilename":"renamed-sample-media.jpg"}'
```

### Скачивание media-файла
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/media/1/download' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --output downloaded-media.bin
```

### Получение thumbnail media-файла
Статус: TODO / НЕ РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/media/1/thumbnail' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Удаление media-файла
Статус: РЕАЛИЗОВАНО

```bash
curl --location --request DELETE '{{baseUrl}}/api/v1/media/1' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Получение checksum'ов
Статус: РЕАЛИЗОВАНО

```bash
curl --location '{{baseUrl}}/api/v1/media/checksums' \
  --header 'Authorization: Bearer {{accessToken}}'
```

### Проверка одного checksum
Статус: TODO / НЕ РЕАЛИЗОВАНО

Тело запроса:

```json
{
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

```bash
curl --location '{{baseUrl}}/api/v1/media/check-exist' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '{"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
```

### Batch-проверка checksum'ов
Статус: TODO / НЕ РЕАЛИЗОВАНО

Тело запроса:

```json
[
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
]
```

```bash
curl --location '{{baseUrl}}/api/v1/media/checksums/exists' \
  --header 'Authorization: Bearer {{accessToken}}' \
  --header 'Content-Type: application/json' \
  --data '["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'
```
