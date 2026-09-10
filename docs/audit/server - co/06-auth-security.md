# 06. Аутентификация и security boundaries

## Источники

[SecurityConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/SecurityConfig.java), [JwtAuthenticationFilter](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/filter/JwtAuthenticationFilter.java), [JwtService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java), [UserService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/UserService.java), [EmailRequestService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java), [EncoderConfig](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/config/EncoderConfig.java), [User](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/model/User.java).

## Регистрация и пароли

[CONFIRMED] RegisterRequest принимает только email/password. @Email + @NotBlank для email, @NotBlank + length4..20 для password. Требование сложности в @Pattern закомментировано. UserService.register проверяет existsByEmail(lowercase), MapStruct переносит поля, задаются USER/enabled=false/banned=false, пароль сохраняется через BCryptPasswordEncoder() без явного strength. Plaintext в users не сохраняется.

[CONFIRMED] EmailRequest ACTIVATE содержит UUID code, user, used=false, createdAt. confirmRegistration(code) проверяет type/used/expiry и в транзакции меняет used=true, enabled=true. Lifetime3 days. Другая существующая ACTIVATE-ссылка пользователя не погашается автоматически. Повтор invalid/used/expired возвращает одинаковый400 BAD_REGISTRATION_REQUEST.

[CONFIRMED] Forgot password по email раскрывает отсутствие учётной записи через400 BAD_REQUEST. Создание reset-кода не вызывает checkAndGenerateCode, поэтому его ограничение3 запроса за3 дня не действует. Email sending синхронный. Password reset принимает JSON, меняет BCrypt hash и помечает использованными reset-коды за последние3 дня; access/refresh не отзывает.

[UNUSED] EmailRequestService.confirmEmail(email,code) не вызывается controllers. В отличие от confirmRegistration он выбирает user по отдельному email, не сверяя владельца кода; это не обнаруженная доступная HTTP-уязвимость текущего маршрута, а риск повторного использования метода.

## Login и JWT

[CONFIRMED] Login ищет user через JPQL u.email=LOWER(:email); email stored lowercase ожидается. Проверяет passwordEncoder.matches, enabled и banned. Любая неудача даёт401 INVALID_CREDENTIALS. Обновляет lastLoginAt (и Hibernate lastUpdate), сохраняет пользователя, затем генерирует access/refresh.

| Свойство | Access | Refresh |
| --- | --- | --- |
| [CONFIRMED] Формат | Signed JWT, compact string | Signed JWT, compact string |
| [CONFIRMED] Claims | sub=email, roles enum values, token_type=ACCESS, iat, exp | sub=email, roles, token_type=REFRESH, iat, exp, jti=UUID |
| [CONFIRMED] Lifetime | 20*60*1000 ms =20 min | 7*24*60*60*1000 ms =7 days |
| [CONFIRMED] Где задан | private final literal JwtService | private final literal JwtService |
| [CONFIRMED] Хранение на сервере | Нет таблицы/blacklist | Полная строка в refresh_token.token |
| [CONFIRMED] Подпись | Base64-decoded token.signing.key → Keys.hmacShaKeyFor → signWith | Тот же ключ и механизм |
| [CONFIRMED] Issuer/audience | Не задаются/не проверяются проектом | Не задаются/не проверяются проектом |
| [CONFIRMED] Refresh rotation | Неприменимо | Нет, endpoint возвращает только access |
| [CONFIRMED] Отзыв | Не реализован | revoked в DB; logout flows |
| [INFERRED] Алгоритм header | Выбирается JJWT по фактическому HMAC key; алгоритм явно в коде не закреплён | То же |

Значения ключей/JWT из локального окружения или примеров не воспроизводятся. Пригодность/длина настоящего ключа и live algorithm не проверялись.

[CONFIRMED] Повторный login не отзывает старые токены; новый refresh имеет независимый jti/DB row. Device ID, IP, user-agent, last-used и session identifier в token entity отсутствуют. Access не содержит jti; выдача одинаковых claims в одну секунду не обязана давать различные строки — уникальный access не обещается.

## Проверка access по запросу

1. [CONFIRMED] resolveBearer принимает только startsWith("Bearer "). Отсутствующий/иной префикс означает отсутствие токена; пустая строка после Bearer попадает в ошибку parser.
2. [CONFIRMED] JWT parser проверяет подпись/exp при извлечении claims. Если token_type != ACCESS, фильтр продолжает без установки principal.
3. [CONFIRMED] Subject извлекается; UserService.loadUserByUsername читает user и EAGER roles из БД. Authorities берутся из текущего User, не из JWT roles.
4. [CONFIRMED] isTokenValid проверяет равенство subject текущему username и expiration. Успех создаёт UsernamePasswordAuthenticationToken непосредственно.
5. [CONFIRMED] ExpiredJwtException либо JwtException/IllegalArgumentException вызывают JsonAuthenticationEntryPoint:401 UNAUTHORIZED. Нет/REFRESH Bearer на protected также приводит к401 после security authorization.
6. [RISK] loadUserByUsername использует Optional.get(); отсутствующий user даёт NoSuchElementException вне перечисленного catch, то есть стабильный401 для удалённого пользователя кодом не обеспечен.

[CONFIRMED] isEnabled/isAccountNonLocked реализованы в User, но этот filter не вызывает AuthenticationManager и не проверяет эти методы перед созданием authenticated token. Refresh flow тоже не проверяет enabled/banned. Проверка присутствует только в login.

[RISK] Поэтому блокировка/отключение учётной записи после login не прекращает запросы и обновление access при имеющемся действующем refresh. Это вывод из явного control flow, не результат эксплуатации.

## Refresh

[CONFIRMED] UserService.refreshToken: findByToken exact full string → найти user по DB userName → JwtService.refreshAccessToken. Сначала revoked →403 REFRESH_TOKEN_REVOKED. Затем validateRefreshToken: signature/parse, token_type=REFRESH, expiration, subject=DB userName. Ошибки →401 INVALID_REFRESH_TOKEN. Если token отсутствует в БД, parser даже не вызывается.

[CONFIRMED] Refresh не обновляет expires/last-used, не выдаёт новый refresh, не продлевает его7 days. Поле expires БД информационное для этого flow; валидация использует exp подписанного JWT. Token с revoked=true даст403 даже если уже expired, при условии user найден.

## Logout и серверное состояние

| Операция/вопрос | Фактический ответ |
| --- | --- |
| [CONFIRMED] Где refresh? | В DB целиком; client получает ту же строку |
| [CONFIRMED] Server-side session? | HTTP SessionCreationPolicy.STATELESS; сохраняемые refresh rows существуют |
| [CONFIRMED] Logout одного | Protected access + body token; lookup, ownership, revoked=true, revokedAt=now |
| [CONFIRMED] Logout всех | Все revoked=false по principal.email становятся true, revokedAt не задаётся |
| [CONFIRMED] Logout others | Проверяется DB наличие/ownership переданного token; остальные revoked=false отзываются |
| [CONFIRMED] Проверяется ли expiry при logout? | Нет; можно revoke свой expired token. Logout-others допускает даже ранее revoked token как «исключение» |
| [CONFIRMED] Повтор logout | Собственный существующий token остаётся revoked;200, новый revokedAt |
| [CONFIRMED] Что после logout? | Refresh получает403; ранее выданный access всё ещё valid до exp |
| [CONFIRMED] Украденный refresh после успешного revoke | Последующий refresh по revoked row не выдаёт access |
| [RISK] Гонка refresh/logout | Нет общей блокировки/transaction validation+revoke; уже прочитанный невозванный token может породить access параллельно logout |
| [CONFIRMED] Password reset | JWT не отзывает; новые logins используют новый пароль |
| [CONFIRMED] Unknown/foreign logout token | 404 REFRESH_TOKEN_NOT_FOUND /403 REFRESH_TOKEN_OWNERSHIP_ERROR |

## Публичные маршруты

[CONFIRMED] permitAll paths: /api/v1/test; /api/v1/auth/register; register/confirm; register/resend; auth/login; auth/refresh-token; auth/password/reset/request; reset/confirm; reset/resend; reset/page. Matcher задаёт paths без ограничения HTTP method; реальные handlers перечислены в05. Публичны также /swagger-ui/**, /swagger-resources/*, /v3/api-docs/**.

[CONFIRMED] Любой другой запрос requires authenticated. JWT filter не исключает public paths: invalid/expired Bearer может заблокировать public login/refresh/reset с401. Valid REFRESH Bearer игнорируется как access; public controller сможет продолжить, если сам token parser не бросил ошибку.

## Права, файлы и границы изоляции

[CONFIRMED] Role USER/ADMIN хранятся, @EnableMethodSecurity включён; role-specific rules/annotations не найдены. ADMIN не даёт отдельного обхода user-scoped file/folder lookups.

[CONFIRMED] Все файловые/папочные endpoints требуют access. getFileForCurrentUser → findByIdAndUserId; getFolderForUser → findByIdAndUserId. Missing и чужие ID скрыты одним404. Upload сначала читает temp, определяет MIME/metadata и только затем проверяет владение folderId. Проверка ownership не сокращает расход на уже принятые байты.

[CONFIRMED] Право на download определяется владельцем FileItem. Владелец StoredObject может отличаться в вручную созданных references; дополнительной ACL нет. При owner delete удаляются все references на объект; текущие upload/copy не создают межпользовательские ссылки. Sharing endpoint отсутствует.

[CONFIRMED] StoragePathResolver делает normalized startsWith(root), FilenameSanitizer заменяет separators/control chars. [RISK] Это лексическая защита; realpath/symlink проверка отсутствует. Нельзя на её основе утверждать защиту от локальной подмены каталогов.

## CORS / CSRF / транспорт

[CONFIRMED] CSRF отключён. Явного cors(), CorsConfigurationSource, @CrossOrigin не найдено. Нет прикладной настройки разрешённых browser origins; CORS не относится к обычному native HTTP Android. TLS server.ssl/forwarded headers/reverse proxy конфигурация в tracked runtime-конфиге отсутствует. Реальный внешний HTTPS неизвестен.

[CONFIRMED] Rate limit login/upload/email, MIME allowlist, антивирус, quota, MFA, password complexity кроме4..20 и user session listing отсутствуют. Эти факты не означают отсутствия внешних сетевых ограничений на развёрнутом сервере.

[RISK] HttpLoggingFilter записывает passwords/token responses и code query, EmailService пишет HTML с reset/activation links. Почтовый debug принудительно true. Разбор в14 и16.
