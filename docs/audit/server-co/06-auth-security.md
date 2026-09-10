# Аутентификация и security boundaries

[CONFIRMED] Источники раздела: SecurityConfig.java, JwtAuthenticationFilter.java, JsonAuthenticationEntryPoint.java, JsonAccessDeniedHandler.java, UserService.java, JwtService.java, EncoderConfig.java, EmailRequestService.java, User.java, Role.java и auth DTO. Ниже описан порядок кода, без проверки deployment.

## Регистрация и password flow

[CONFIRMED] RegisterRequest: email @NotBlank/@Email, password @NotBlank/4..20; обязательность регистра/цифр/спецсимволов закомментирована. UserService.register проверяет existsByEmail(lowercase), MapStruct → User, назначает USER, lowercase email, BCryptPasswordEncoder, banned=false, enabled=false, save user → save ACTIVATE code → send email. Display name не принимает. Unique race не переводится в DuplicateEmailException.

[CONFIRMED] Код email — UUID string в email_requests, срок 3 дня (оба типа), одноразовость через used. Проверка code/type/used/expiration объединяет отказы в BAD_REGISTRATION_REQUEST. confirmRegistration активирует именно пользователя из code row. Транзакция сохраняет enabled/used dirty checking.

[UNUSED] confirmEmail(email,code) существует, но маршрутом не вызывается; он берёт User по отдельно переданному email без проверки равенства владельцу code. Это не текущий HTTP exploit endpoint, но скрытое отличие от активного confirmRegistration. checkAndGenerateCode ограничивает 3 запроса за 3 дня, но активные register/forgotPassword вызывают generateEmailRequest напрямую.

[CONFIRMED] Forgot password раскрывает наличие email через 400 для unknown; нет @Email, только @NotBlank. Reset confirm принимает JSON password/code и в транзакции обновляет BCrypt hash, использует все reset-коды последних 3 дней. Refresh/access не отзываются. Resend/page — 501; письмо ведёт на stub page.

## Login и JWT

[CONFIRMED] login: lookup email=LOWER(input), passwordEncoder.matches, enabled && !banned, lastLoginAt=now/save, generateAccessToken, generateRefreshToken/save. Нет общей транзакции; новый refresh для каждого login, старые не отзываются. Lowercase storage обеспечивается сервисом, SQL unique email сам по себе case-sensitive. loadUserByUsername вызывает Optional.get(), а не осмысленный UsernameNotFoundException.

| JWT | Claims | Срок / хранение |
| --- | --- | --- |
| Access | roles, token_type=ACCESS, sub=email, iat, exp | 20 минут, в БД не хранится |
| Refresh | roles, token_type=REFRESH, jti=UUID, sub=email, iat, exp | 7 дней, полная строка в refresh_token.token |

[CONFIRMED] Signing key загружается из token.signing.key как Base64 → Keys.hmacShaKeyFor, signWith(Key); parser проверяет подпись тем же ключом. Алгоритм не зафиксирован явно в application code и зависит от ключа/выбора JJWT. Не следует обещать HS256 только по слову JWT. issuer/audience/kid/nonce не задаются; роли в claims отражают момент выдачи. Authorities protected request берутся из загруженного User, а не из roles claim.

[CONFIRMED] Access TTL и refresh TTL — private final литералы JwtService, не конфигурационные properties. Токен exp — Date epoch; refresh DB expires преобразуется в LocalDateTime через systemDefault zone. Refresh validator доверяет exp из JWT, не DB expires.

## Protected request flow

1. [CONFIRMED] HttpLoggingFilter оборачивает запрос/ответ до security.
2. [CONFIRMED] JwtAuthenticationFilter извлекает только prefix «Bearer ».
3. [CONFIRMED] При отсутствии prefix передаёт запрос дальше без authentication.
4. [CONFIRMED] Подписанный token разбирается; token_type должен быть ACCESS. Другой тип пропускается без аутентификации.
5. [CONFIRMED] По sub загружается User. isTokenValid сравнивает username и exp; затем напрямую создаётся authenticated UsernamePasswordAuthenticationToken с authorities.
6. [CONFIRMED] @AuthenticationPrincipal — объект User. Для file/folder service ownership lookup использует его id.
7. [CONFIRMED] ExpiredJwtException/JwtException/IllegalArgumentException → clearContext + entryPoint 401; невалидный JWT даёт 401 даже public route.
8. [CONFIRMED] SecurityConfig: STATELESS, anyRequest authenticated, CSRF disabled. HTTP session для auth не создаётся.

[RISK] enabled/banned/isAccountNonLocked не проверяются фильтром при создании authenticated token. Если после login аккаунт заблокирован, подпись/exp+User всё ещё достаточны. Refresh также не проверяет enabled/banned. Граница блокировки фактически есть только у login.

[RISK] При удалённом пользователе Optional.get() выбрасывает NoSuchElementException вне перечисленных JWT catches; единый 401 для такого bearer не гарантирован. User deletion API отсутствует, условие возможно при внешней административной правке.

## Refresh и отзыв

[CONFIRMED] Refresh endpoint public: точный token lookup в БД → User по row.userName → revoked check → тип REFRESH/exp/sub/signature → новый access. Unknown token/User/parse validation → 401 INVALID_REFRESH_TOKEN; revoked → 403 REFRESH_TOKEN_REVOKED до проверки expiration. Refresh JWT не заменяется и срок не продлевается.

| Операция | Проверка | Изменение | После |
| --- | --- | --- | --- |
| logout | protected User, row exists, row.userName=email | revoked=true, revokedAt=now | Refresh не пригоден, access продолжает проходить до exp |
| logout-all | protected User | Все revoked=false по email → true | Не заполняет revokedAt; new login снова возможен |
| logout-others | protected User, сохранённая исключаемая row принадлежит ему | Остальные revoked=false → true | Исключаемый token не обязательно active/неистёкший |
| password reset | Валидный reset code | Hash + used codes | Tokens остаются действующими |

[CONFIRMED] После завершённого logout украденный **отозванный** refresh выдаёт 403 на следующую обычную проверку. Украденный access не отзывается. Другие refresh не затронуты одиночным logout. [RISK] Refresh и revoke не сериализованы: запрос refresh, прочитавший revoked=false до конкурентного logout, может успеть выдать access. Это условный interleaving, не воспроизведённый тест.

[CONFIRMED] Server-side state существует в refresh_token и users/email_requests, несмотря на отсутствие HTTP sessions. Нет token family, device ID, last-used field, refresh rotation/reuse detection, access denylist, endpoint списка устройств/сессий.

## Permissions и изоляция

[CONFIRMED] Role USER/ADMIN → ROLE_*; @EnableMethodSecurity включён, но @PreAuthorize/@Secured/role-specific guards на production методах не найдены. Поэтому роль ADMIN сама по себе не даёт отдельного публичного API или обхода ownership.

[CONFIRMED] Файл: findByIdAndUserId; папка: findByIdAndUserId. Ошибки чужого и missing ID — 404. List/checksums scope user; existence дополнительно folder. Upload проверяет target folder owner, но только после чтения temp и metadata.

[CONFIRMED] Download доверяет StoredObject, доступному через owned FileItem; отдельного запрета StoredObject.user != FileItem.user нет. Так модель допускает shared reference при создании извне; HTTP sharing API нет. Owner delete физического объекта удаляет все ссылки на него. Не следует считать эту ветку готовой моделью sharing ACL.

## Публичные маршруты

[CONFIRMED] /api/v1/test; /auth/register, /auth/register/confirm, /auth/register/resend; /auth/login, /auth/refresh-token; /auth/password/reset/request, /confirm, /resend, /page — относительно /api/v1. Для /page GET и POST. Матчеры не ограничены HTTP method, но обработчики имеют конкретные методы. Swagger /swagger-ui/**, /swagger-resources/*, /v3/api-docs/** также permitAll.

[CONFIRMED] CORS-конфигурации, @CrossOrigin и http.cors() нет. Это не «разрешены все origins». Android native HTTP не применяет browser CORS; браузерные preflight/кросс-origin сценарии без runtime-проверки не обещаны. CSRF выключен. HTTPS/SSL server config, rate limiting, CAPTCHA, 2FA, account lockout counters, quota, malware scanning не найдены.

[RISK] HTTP body/response logging и email HTML logging раскрывают пароли, токены, code и персональные поля; детали в 14-observability.md. В отчётах реальные значения из smoke/config не воспроизводятся.

