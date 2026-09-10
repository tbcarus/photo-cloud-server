# 02. Auth-контракт для клиента

Срез: 2026-09-10. [CONFIRMED] Все пути ниже имеют prefix /api/v1. Код сервера — источник; live token/secret values не включены.

## Login и сохраняемые данные

POST /auth/login, public, JSON:
~~~json
{"email":"person@example.com","password":"example-password"}
~~~
[CONFIRMED] email not blank + valid email, password not blank length4..20. Успех200:
~~~json
{"accessToken":"<access-jwt>","refreshToken":"<refresh-jwt>"}
~~~

[CONFIRMED] Нет expiresIn/tokenType/userId в login response. Access действует20min от issue; refresh7days. Для protected requests нужен `Authorization: Bearer <accessToken>`, точный prefix. JWT подписан, payload содержит sub=email, roles, token_type ACCESS/REFRESH, iat/exp; refresh также jti. Клиенту не нужен ключ подписи.

[INFERRED] Для последующего доступа/refresh/logout клиенту требуется сохранить обе строки и привязку к текущему аккаунту. Способ хранения на Android — вне server-контракта, сервер его не задаёт. Нужный profile ID можно прочитать GET /profile. Источник истины валидности — серверный ответ, локальное чтение exp помогает планировать запрос, но не доказывает отсутствие revoke.

[CONFIRMED] Неверный email/password, disabled или banned user при login дают одинаковый401 INVALID_CREDENTIALS. Каждый successful login создаёт новый refresh, прежние не отзываются; это не «войти на одно устройство». Device ID сервер не принимает.

## Refresh

POST /auth/refresh-token, public, JSON:
~~~json
{"refreshToken":"<saved-refresh-jwt>"}
~~~
Успех200:
~~~json
{"accessToken":"<new-access-jwt>"}
~~~

[CONFIRMED] Refresh string остаётся прежней, её expiration не продлевается. Нет rotation и endpoint проверки срока отдельно. Ошибки:400 blank/body;401 INVALID_REFRESH_TOKEN unknown/invalid/expired/wrong type;403 REFRESH_TOKEN_REVOKED при revoked.

[CONFIRMED] Public означает отсутствие требования access, но JWT filter всё равно разбирает присланный Authorization. Expired/malformed access header даст401 UNAUTHORIZED до refresh body. Поэтому этот контракт не требует отправлять access header на refresh/login/register/reset. Refresh в Bearer не заменяет access на protected endpoints.

## Logout

| Operation | Request | Success | Effect |
| --- | --- | --- | --- |
| [CONFIRMED] POST /auth/logout | Valid access + JSON {refreshToken} nonblank |200 message Logged out successfully | Один свой refresh revoked |
| [CONFIRMED] POST /auth/logout-all | Valid access, без body |200 message All logged out successfully | Все текущие неотозванные refresh account |
| [CONFIRMED] POST /auth/logout-others | Valid access + JSON {refreshToken} |200 message All other logged out successfully | Все неотозванные refresh account кроме указанного |

[CONFIRMED] Logout/others: чужой refresh403 REFRESH_TOKEN_OWNERSHIP_ERROR; неизвестный404 REFRESH_TOKEN_NOT_FOUND. Повтор own existing logout200. Токен body не обязан быть ещё unexpired/unrevoked, проверяются наличие и ownership. Logout требует действующий access: с одним просроченным access он не выполнится.

[CONFIRMED] Ранее выданные access после logout остаются usable до exp. Украденный refresh, помеченный revoked, больше не обновляет access при следующей проверке; уже выпущенный access не исчезает. Гонка concurrent refresh/logout не имеет отдельного client-visible разрешения.

[INFERRED] Локальный logout клиента должен определять, какие сохранённые credentials/очереди он очищает; сервер не командует локальной БД и не подтверждает её очистку. Нельзя считать пропажу локального токена доказательством серверного revoke, если запрос не завершён.

## Регистрация / recovery

[CONFIRMED] POST /auth/register с теми же email/password validation создаёт disabled account,201 message. GET /auth/register/confirm?code=... →200 plain text; invalid/used/expired400 BAD_REGISTRATION_REQUEST. Код живёт3days. Resend регистрации501.

[CONFIRMED] POST /auth/password/reset/request?email=... создаёт письмо; неизвестный email400 BAD_REQUEST. POST /auth/password/reset/confirm принимает JSON {password,code}, password4..20 и оба nonblank;200 message. Legacy query-only confirm400. Код reset живёт3days, одноразовый по проверке used.

[PARTIAL] Письмо ведёт на GET /auth/password/reset/page?code=..., который возвращает501 JSON. POST page/resend тоже501. Backend JSON confirm реализован. Действующие access/refresh при reset **не отзываются**.

## 401 / 403 и account state

| Code | Что означает клиенту |
| --- | --- |
| [CONFIRMED]401 UNAUTHORIZED | Нет пригодного access; invalid header на public тоже возможен |
| [CONFIRMED]401 INVALID_CREDENTIALS | Login отклонён |
| [CONFIRMED]401 INVALID_REFRESH_TOKEN | Этот refresh не пригоден, повтор без нового входа не поможет |
| [CONFIRMED]403 REFRESH_TOKEN_REVOKED | Refresh отозван |
| [CONFIRMED]403 REFRESH_TOKEN_OWNERSHIP_ERROR | Смешаны token/account в logout |
| [CONFIRMED]403 FORBIDDEN | Общий security denial; отдельной role-specific API политики нет |

[RISK] Сервер не проверяет enabled/banned заново в access/refresh flow. Клиент не должен выводить из этого продуктовую политику разрешения заблокированным пользователям; это известное свойство текущей реализации.

Источник: [UserService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/UserService.java), [JwtService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java), [filter](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/JwtAuthenticationFilter.java), [auth DTO](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/dto).
