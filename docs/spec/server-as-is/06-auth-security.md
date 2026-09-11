# Authentication и authorization

## Behaviour

### Регистрация, login и пароль

Регистрация принимает email/password, нормализует email в lowercase, сохраняет BCrypt hash, USER role, enabled=false и banned=false. BCryptPasswordEncoder создаётся с настройками по умолчанию. Password length 4..20 действует на register/login/reset; complexity rule не применяется. ACTIVATE UUID-код действует 3 дня. Подтверждение проверяет type/used/expiry, транзакционно включает пользователя и помечает текущий код использованным. Прочие ACTIVATE-коды автоматически не погашаются.

Письмо активации содержит ссылку на `GET /api/v1/auth/register/confirm?code=<UUID>`. Origin и context path сервер собирает из request scheme/host/port/context. Подтверждение выполняется переходом по GET-ссылке; успешный response — `200 text/plain`.

Login читает user, проверяет password, enabled и banned; любой из этих отказов даёт 401 INVALID_CREDENTIALS. Затем обновляет lastLoginAt, сохраняет user и выдаёт access/refresh. Повторный login создаёт ещё одну refresh row, предыдущие не отзывает.

### JWT

| Свойство | Access | Refresh |
| --- | --- | --- |
| TTL | 20 минут, Java literal | 7 дней, Java literal |
| Claims | sub=email, roles, token_type=ACCESS, iat, exp | sub=email, roles, token_type=REFRESH, iat, exp, jti=UUID |
| Подпись | HMAC key из Base64 JWT_KEY через JJWT signWith | Тот же key |
| Persisted state | Отсутствует | Полная JWT-строка, userName, expires, revoked, revokedAt |
| Отзыв | Не реализован | Проверка revoked в refresh flow |
| Продление/ротация | Новый JWT по refresh | Отсутствуют |

Конкретный HS algorithm зависит от ключа, а не явно закреплённого параметра. Issuer/audience project code не задаёт. DB `expires` — копия JWT exp в LocalDateTime, но refresh валидируется по подписанному exp. При revoked=true возвращается 403 до проверки JWT expiry, если user существует; unknown token/user даёт 401 INVALID_REFRESH_TOKEN.

### Access filter

Точный префикс `Bearer ` извлекает токен. Парсер проверяет подпись/expiry. Тип, отличный от ACCESS, не устанавливает principal. Для ACCESS subject разрешается в User через БД; authorities берутся из текущих User.roles. Сервис проверяет subject и expiry, затем фильтр напрямую создаёт authenticated token. Enabled/banned при этом не проверяются; они проверяются только login. Malformed/expired JWT даёт JSON 401 UNAUTHORIZED.

Отсутствующий user для access приводит к Optional.get за пределами JWT catch; стабильный 401 для этого случая не обеспечен. Для refresh отсутствующий user, напротив, преобразуется в INVALID_REFRESH_TOKEN. HTTP session policy STATELESS; persisted refresh rows при этом существуют.

### Refresh и logout

Refresh public: exact token lookup → user lookup → revoked → JWT signature/type/exp/subject → новый access. Запись не изменяется, refresh не продлевается и не потребляется.

| Endpoint | Проверки и эффект |
| --- | --- |
| POST auth/logout | Access + существующий принадлежащий user refresh; revoked=true, revokedAt=now |
| POST auth/logout-all | Access; все revoked=false по userName →true; revokedAt не заполняется |
| POST auth/logout-others | Access + существующий собственный refresh-исключение; остальные revoked=false →true, revokedAt не заполняется |

Logout проверяет наличие/ownership, не expiry или текущий revoked переданного refresh. Logout-others может исключить уже expired/revoked token; это не делает его действующим. Повтор single logout собственного токена возвращает 200 и обновляет revokedAt. Unknown refresh для logout —404, чужой —403. Уже выданный access после logout продолжает работать до exp. Read/validate refresh и revoke не образуют единую сериализованную операцию.

### Password reset и почта

Reset request принимает email query, создаёт PASSWORD_RESET code и синхронно вызывает SMTP. Unknown email даёт 400 BAD_REQUEST. Reset confirm принимает JSON password/code, проверяет код и меняет BCrypt hash. Текущий код и reset-коды пользователя за последние 3 дня помечаются used. Токены не отзываются.

Email-ссылка построена из request scheme/host/port/context и ведёт на GET reset/page, возвращающий 501. Сам JSON confirm реализован. Email-коды имеют проверку одноразовости флагом, но не условную запись/lock/version для exactly-once при конкурентном использовании. Лимитер checkAndGenerateCode UNUSED; активные register/reset request вызывают генерацию напрямую. Alternate confirmEmail(email,code) UNUSED и не является действующим HTTP-путём.

### Public и protected routes

Public: `/api/v1/test`; register, register/confirm, register/resend; auth/login, auth/refresh-token; password/reset/request, confirm, resend, page; Swagger/UI/API-docs paths из [05](05-api.md). Matchers разрешают пути без ограничения HTTP method; наличие handler определяется таблицей API. Всё остальное requires authenticated.

JWT filter работает и на public URL: expired/invalid Bearer может дать 401 до refresh/login/reset. Role USER/ADMIN хранится; отдельного bypass owner checks для ADMIN нет. Method security включён, role-based annotations/rules в active API отсутствуют.

FileItem и Folder выбираются по id + userId, поэтому чужой и отсутствующий объект дают один 404. Для download проверяется владелец FileItem; дополнительной ACL StoredObject нет. Физическая изоляция дополняется userId в пути, нормализацией пути и санитарной обработкой filename. SQL сам по себе не гарантирует совпадение owners всех связанных строк.

CSRF выключен. Прикладная CORS policy не задана; из этого не следует запрет любого браузерного клиента. Собственный browser UI отсутствует, same-origin/внешний proxy — отдельные условия deployment. TLS termination и внешние ограничения не установлены.

## Known security risks

См. [RISK-SRV-001–006, 023–025, 027, 032](16-risks.md): чувствительные логи, продолжение доступа после ban/reset/logout, reusable refresh в БД, rate limits, слабые пароли, enumeration, внешняя конфигурация и незавершённый reset flow.
