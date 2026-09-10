# 06. Authentication & Security

---

## 1. Общая модель

**[CONFIRMED]** Stateless JWT-аутентификация поверх Spring Security 6.

`SecurityConfig.filterChain()`:

```java
.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.authorizeHttpRequests(... permitAll-список ... .anyRequest().authenticated())
.exceptionHandling(ex -> ex.authenticationEntryPoint(...).accessDeniedHandler(...))
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.csrf(csrf -> csrf.disable());
```

**[CONFIRMED]** `@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)` включён, но **ни одной аннотации `@PreAuthorize`/`@Secured`/`@RolesAllowed` в проекте нет** — [UNUSED].

---

## 2. Регистрация

**[CONFIRMED]** `RegisterController.register()` → `UserService.register()`:

1. `userRepository.existsByEmail(email.toLowerCase())` → при совпадении `DuplicateEmailException` → `409`.
2. MapStruct `UserRegisterMapper.toUser(dto)`.
3. `roles = {Role.USER}`, `email = email.toLowerCase()`, `password = BCrypt(password)`, `banned = false`, `enabled = false`.
4. `userRepository.save(user)`.
5. `emailRequestService.generateEmailRequest(user, ACTIVATE)` → строка в `email_requests` с `code = UUID`.
6. `emailService.sendEmail(...)`; `MessagingException` ловится и логируется.

**Активация:** `GET /auth/register/confirm?code=...` → `EmailRequestService.confirmRegistration()`:
проверка `used == false && type == ACTIVATE && !isExpired()` (срок — 3 дня от `createdAt`), затем `used = true` и `user.enabled = true` (через dirty checking внутри `@Transactional`).

**[CONFIRMED][RISK]**
- Пароль ограничен `@Size(min=4, max=20)`; закомментирован `@Pattern` со сложностью — требований к сложности нет.
- Rate-limit регистрации/повторной отправки отсутствует; `EmailRequestService.checkAndGenerateCode()` (лимит 3 запроса за 3 дня) написан, но **не вызывается**.
- `409` на существующий email → user enumeration.
- `register()` не транзакционен: сбой между шагами 4 и 5 оставит пользователя без кода активации при отсутствующем resend.

---

## 3. Login

**[CONFIRMED]** `UserService.login()`:

```
findByEmailIgnoreCase(email)        → нет → InvalidCredentialsException (401)
passwordEncoder.matches(raw, hash)  → нет → InvalidCredentialsException (401)
!enabled || banned                  → да  → InvalidCredentialsException (401)
user.lastLoginAt = now(); save(user)
accessToken  = jwtService.generateAccessToken(user)
refreshToken = jwtService.generateRefreshToken(user)   // + строка в refresh_token
return LoginResponse(accessToken, refreshToken)
```

**[CONFIRMED]** Все четыре причины отказа неразличимы для клиента — это защита от enumeration на login (в отличие от register/reset).

**[CONFIRMED]** `AuthenticationManager`/`DaoAuthenticationProvider` **не используются**: сверка пароля выполняется вручную в сервисе. `UserService implements UserDetailsService` нужен только фильтру.

**[CONFIRMED][RISK]** Ни блокировки после N неудачных попыток, ни задержки, ни CAPTCHA — brute force не ограничен.

---

## 4. Структура JWT

**[CONFIRMED]** `JwtService`, библиотека JJWT 0.12.6.

### Access token

| Элемент | Значение |
| --- | --- |
| Алгоритм | HMAC-SHA (`Keys.hmacShaKeyFor(Base64.decode(token.signing.key))`); конкретный HS256/384/512 выбирается JJWT по длине ключа |
| `sub` | email пользователя |
| `roles` | сериализованный `Set<Role>` |
| `token_type` | `"ACCESS"` |
| `iat` | момент выпуска |
| `exp` | `iat + 20 минут` (`expirationTime = 20*60*1000`, поле `private final long`, **не конфигурируемо**) |
| `jti` | **отсутствует** |
| Хранение на сервере | **нет** |

### Refresh token

| Элемент | Значение |
| --- | --- |
| Алгоритм | тот же ключ, тот же алгоритм |
| `sub` | email |
| `roles` | есть (избыточно) |
| `token_type` | `"REFRESH"` |
| `jti` | `UUID.randomUUID()` |
| `exp` | `iat + 7 дней` (`refreshExpirationTime = 7*24*60*60*1000`, **не конфигурируемо**) |
| Хранение на сервере | строка в `refresh_token` (`token` — весь JWT, `user_name`, `expires`, `revoked`, `revoked_at`) |

**[CONFIRMED][RISK]** Access и refresh подписываются **одним и тем же ключом**; различаются только claim'ом `token_type`. Безопасность полностью держится на проверке этого claim'а в `JwtAuthenticationFilter` (строки 45–49) и в `JwtService.validateRefreshToken()`.

**[CONFIRMED][RISK]** TTL захардкожены как `private final` поля класса — вынести их в `application.yml` без изменения кода невозможно. `TODO.txt` п.7 предполагал другие значения (access 2 часа, refresh 2 месяца).

**[CONFIRMED]** Ротации ключа подписи нет: `kid` не выставляется, поддержка нескольких ключей отсутствует.

---

## 5. Валидация токена при каждом запросе

**[CONFIRMED]** `JwtAuthenticationFilter.doFilterInternal()`:

```
1. resolveBearer(request): заголовок Authorization, префикс "Bearer " (регистрозависимо)
   нет токена → продолжить цепочку анонимно (позже 401 от entry point на защищённом URL)
2. jwtService.extractTokenType(token)   // парсит и проверяет подпись
   != "ACCESS" → продолжить цепочку БЕЗ аутентификации (refresh token не аутентифицирует)
3. extractUserName(token) → userService.loadUserByUsername(username)
4. jwtService.isTokenValid(token, userDetails):
      subject == userDetails.getUsername() && !isTokenExpired(token)
5. → UsernamePasswordAuthenticationToken в SecurityContext
catch ExpiredJwtException            → clearContext + 401 через entryPoint
catch JwtException | IllegalArgument → clearContext + 401 через entryPoint
```

**[CONFIRMED][RISK]** Критические наблюдения:

1. `isTokenValid()` **не проверяет** `user.isEnabled()` и `user.isAccountNonLocked()`. Забаненный или отключённый пользователь с ещё живым access token продолжает работать до 20 минут. Ни `UserDetailsChecker`, ни `AccountStatusUserDetailsChecker` в цепочке нет.
2. `UserService.loadUserByUsername()` — `return userRepository.findByEmailIgnoreCase(username).get();` — **`Optional.get()` без проверки**. Для валидного JWT удалённого/переименованного пользователя это `NoSuchElementException` внутри фильтра → не ловится (`catch` покрывает только `JwtException`/`IllegalArgumentException`) → `500`, а не `401`.
3. При «шаге 2» токен типа REFRESH просто пропускается дальше без ошибки; на защищённом URL это выльется в `401` — поведение корректное, но диагностически неотличимое от «нет токена».
4. `loadUserByUsername()` выполняет запрос к БД **на каждый аутентифицированный HTTP-запрос** — кэша нет.

---

## 6. Refresh flow

**[CONFIRMED]** `POST /auth/refresh-token` (публичный) → `UserService.refreshToken()`:

```
1. jwtService.getRefreshToken(rawToken)
       = refreshTokenRepository.findByToken(raw)
       нет → InvalidRefreshTokenException → 401
2. email = tokenDb.getUserName()
3. userRepository.findByEmailIgnoreCase(email)
       нет → InvalidRefreshTokenException → 401
4. jwtService.refreshAccessToken(user, tokenDb):
       tokenDb.isRevoked()          → TokenRevokedException → 403
       validateRefreshToken(tokenDb):
           token_type == "REFRESH"  иначе → 401
           !isTokenExpired(token)   иначе → 401
           subject == tokenDb.userName иначе → 401
           JwtException/IllegalArgumentException → 401
       return generateAccessToken(user)
5. RefreshResponse(accessToken)
```

**[CONFIRMED]** Записей в БД операция не делает: ни `lastUsedAt`, ни ротации, ни продления.

**[CONFIRMED][RISK]**
- `enabled`/`banned` **не проверяются** — забаненный пользователь обновляет access token в течение всего срока refresh.
- Refresh token не ротируется → украденный токен работает до истечения 7 дней или явного logout.
- Отсутствует детекция повторного использования (replay detection), обычная для refresh-rotation схем.
- `findByToken` по `TEXT`-колонке без индекса.

---

## 7. Logout и отзыв

**[CONFIRMED]** Три варианта:

| Endpoint | Метод `JwtService` | Что делает | `revoked_at` |
| --- | --- | --- | --- |
| `/auth/logout` | `revokeOwnedToken(token, user)` | находит токен → проверяет владение → `revoke()` | **заполняется** |
| `/auth/logout-all` | `revokeAll(user)` | все токены пользователя с `revoked=false` → `revokeList()` | **НЕ заполняется** |
| `/auth/logout-others` | `revokeOtherOwnedToken(token, user)` | проверяет владение переданным → `revokeOther()` → `revokeList()` для всех остальных | **НЕ заполняется** |

**[CONFIRMED][INCONSISTENCY]** `revokeList()` (строки 116–119 `JwtService`) не выставляет `revokedAt`, в отличие от `revoke()`.

**[CONFIRMED]** Проверка владения (`assertOwnedBy`) сравнивает `refreshToken.getUserName()` с `user.getUsername()` (email). При несовпадении — `RefreshTokenOwnershipException` → `403`.

**[CONFIRMED][RISK]** `logout` и `logout-others` требуют **валидный access token**. Если access истёк (20 мин), клиент не может выполнить logout, не сделав сначала refresh. Это делает «выход по кнопке» после долгого простоя двухшаговым.

**[CONFIRMED]** Отозванный токен остаётся в таблице навсегда — очистки нет.

---

## 8. Ответы на контрольные вопросы

### Где хранится refresh token?
**[CONFIRMED]** На сервере — целиком, как строка JWT, в колонке `refresh_token.token` (`TEXT`), в открытом виде (не хеш). На клиенте — по усмотрению клиента (сервер cookie не устанавливает).

**[RISK]** Утечка дампа БД раскрывает действующие refresh token'ы напрямую. Общепринятая практика — хранить хеш.

### Можно ли отозвать токен?
**[CONFIRMED]** Refresh token — да (`revoked = true`, три варианта logout).
Access token — **нет**. Он не хранится и не проверяется по списку отзыва; после logout остаётся валидным до истечения **20 минут**.

### Что происходит при повторном login?
**[CONFIRMED]** Создаётся **новая** пара токенов и **новая** строка в `refresh_token`. Предыдущие токены не отзываются и продолжают работать. Ограничения количества активных сессий нет. Закомментированный код переиспользования существующего refresh token остался в `JwtService.generateRefreshToken()` (строки 58–61).

### Что происходит после logout?
**[CONFIRMED]** Соответствующая строка `refresh_token` помечается `revoked=true`. Обновить access token этим refresh больше нельзя (`403 REFRESH_TOKEN_REVOKED`). Уже выданный access token продолжает работать до `exp`.

### Может ли украденный refresh token использоваться после logout?
**[CONFIRMED] Нет** — при условии, что был вызван logout именно для этого токена (или `logout-all`). `refreshAccessToken()` первым делом проверяет `isRevoked()`.

**[CONFIRMED][RISK] Но:** если refresh token украден и logout не выполнялся, он работает до 7 дней; смена пароля его **не аннулирует**; детекции параллельного использования нет.

### Есть ли server-side session state?
**[CONFIRMED]** HTTP-сессий нет (`STATELESS`, `JSESSIONID` не выдаётся). Единственное серверное состояние аутентификации — таблица `refresh_token`.

### Какие endpoint'ы публичные?
**[CONFIRMED]** Точный список из `SecurityConfig` (11 своих + 3 springdoc-шаблона):

```
GET  /api/v1/test
POST /api/v1/auth/register
GET  /api/v1/auth/register/confirm
POST /api/v1/auth/register/resend
POST /api/v1/auth/login
POST /api/v1/auth/refresh-token
POST /api/v1/auth/password/reset/request
POST /api/v1/auth/password/reset/confirm
POST /api/v1/auth/password/reset/resend
GET  /api/v1/auth/password/reset/page      (один matcher покрывает GET и POST)
POST /api/v1/auth/password/reset/page
/swagger-ui/**  /swagger-resources/*  /v3/api-docs/**
```

Всё остальное — `anyRequest().authenticated()`.

**[CONFIRMED]** Matcher'ы указаны точными строками (без wildcard), поэтому опечатка в пути контроллера немедленно закроет endpoint — это скорее плюс. Пути собираются из констант контроллеров, что исключает рассинхронизацию.

**[CONFIRMED][RISK]** `/v3/api-docs/**` публичен → полная спецификация API доступна анонимно.

---

## 9. Пароли

**[CONFIRMED]**

| Аспект | Значение |
| --- | --- |
| Алгоритм | BCrypt, `new BCryptPasswordEncoder()` — strength по умолчанию **10** |
| Соль | генерируется BCrypt автоматически |
| Хранение | `users.password VARCHAR(128)` |
| Требования | длина 4–20; сложность **не проверяется** (`@Pattern` закомментирован) |
| Смена пароля из профиля | **не реализована** (только через reset по email) |
| Проверка старого пароля при смене | отсутствует (смена возможна только по email-коду) |

**[RISK]** Минимум 4 символа без требований к сложности — очень слабая политика.

---

## 10. Роли и права

**[CONFIRMED]**

- Роли: `USER`, `ADMIN`; хранятся в `user_roles`, попадают в `getAuthorities()` как `ROLE_USER`/`ROLE_ADMIN` и в claim `roles` обоих токенов.
- **Ни одной проверки роли в коде нет.** Ни `hasRole()` в `SecurityConfig`, ни `@PreAuthorize`.
- `ADMIN` никому не назначается (при регистрации всегда `{USER}`), административных endpoint'ов нет.

**[CONFIRMED] Фактическая модель авторизации — ownership-based, а не role-based:** доступ к объекту разрешён, если объект принадлежит текущему пользователю. Реализовано на уровне запросов:

| Проверка | Метод |
| --- | --- |
| Файл | `FileItemRepository.findByIdAndUserId(id, userId)` |
| Папка | `FolderRepository.findByIdAndUserId(id, userId)` |
| Список файлов | `findAllByUserId` / `findAllByUserIdAndFolderId` |
| Checksums | `findAllChecksumsAndOriginalFilenamesByUserId` / `findExistingChecksumsInFolder` |
| Refresh token | `assertOwnedBy()` |

---

## 11. Доступ одного пользователя к файлам другого

**[CONFIRMED]** Невозможен через API:

| Сценарий | Результат | Подтверждение |
| --- | --- | --- |
| `GET /files/{чужой id}` | `404 FILE_ITEM_NOT_FOUND` | тест `foreignMissingAndMissingPhysicalFilesReturnNotFound` |
| `GET /files/{чужой id}/download` | `404` | тот же тест |
| `DELETE /files/{чужой id}` | `404` | там же |
| `GET /files?folderId={чужой}` | `404` | тест `foreignFolderCannotBeUsedForListUploadCopyOrMove` |
| upload/copy/move в чужую папку | `404` | тот же тест |
| `POST /files/checksums/exists` с чужим `folderId` | `404` | тест `checksumExistsRejectsForeignOrMissingFolder` |
| Любая операция с чужой папкой | `404` | тест `cannotWorkWithForeignFolder` |
| `checksums/exists` для checksum другого пользователя | всегда `missing` | тест `checksumExistsIsScopedToFolderAndUser` |

**[CONFIRMED]** Сознательное решение: `403` не используется для чужих объектов, чтобы не подтверждать их существование (зафиксировано в `docs/api-folder-contract.md` §Security).

**[CONFIRMED]** Физический доступ к файлу возможен только через `FileItem`: `filePath`/`filename` наружу не отдаются, а `StoragePathResolver.resolve()` отбрасывает пути, выходящие за `storage.root`.

---

## 12. CORS

**[CONFIRMED]** **Не настроен вообще.** В `SecurityConfig` нет `.cors(...)`, в проекте нет `CorsConfigurationSource`, `WebMvcConfigurer` или `@CrossOrigin`.

Следствия:
- Spring Security не добавляет CORS-заголовки; preflight `OPTIONS` пойдёт по общей цепочке и на защищённом URL получит `401`.
- Любой браузерный фронтенд с другого origin работать не будет.
- Android/OkHttp/curl это не затрагивает — CORS применяется только браузерами.

**[RISK]** MEDIUM — блокер для веб-клиента, но не проблема безопасности как таковая.

---

## 13. CSRF

**[CONFIRMED]** Отключён явно: `.csrf(csrf -> csrf.disable())`.

**[INFERRED]** Обосновано: API stateless, аутентификация через заголовок `Authorization` (не cookie), поэтому классическая CSRF-атака неприменима. Риск появится только при переходе на cookie-аутентификацию.

---

## 14. Авторизация загрузки

**[CONFIRMED]**

- `POST /files` и `POST /files/upload` требуют аутентификации (не в permitAll).
- Владелец берётся из `@AuthenticationPrincipal User` — подделать `userId` в запросе невозможно, такого параметра нет.
- `folderId` (если передан) проверяется через `FolderService.getFolderForUser(folderId, user)` → чужая папка = `404`.
- Тип файла **не ограничивается**: `FileType.fromMimeType()` классифицирует что угодно, включая `application/x-msdownload` → `OTHER`. Whitelist/blacklist MIME отсутствует.
- Размер ограничен двумя барьерами: `spring.servlet.multipart.max-file-size: 110MB` (Tomcat/Spring) и `storage.max-file-size-bytes: 104857600` (~100 MiB, проверяется потоково в `FileUtils`).
- Квоты на пользователя (общий объём, число файлов) **нет**.

**[RISK]** Аутентифицированный пользователь может загрузить произвольное количество файлов по 100 MiB — DoS по диску не ограничен.

---

## 15. Обработка 401 и 403

**[CONFIRMED]**

| Ситуация | Обработчик | Статус | Тело |
| --- | --- | --- | --- |
| Нет токена на защищённом URL | `JsonAuthenticationEntryPoint` | `401` | `{"id":"<uuid>","code":"UNAUTHORIZED","message":"Unauthorized: access token expired or invalid","fieldErrors":null}` |
| Истёкший access token | `JwtAuthenticationFilter` → `entryPoint.commence()` | `401` | то же |
| Malformed JWT | то же | `401` | то же |
| Refresh token вместо access | фильтр пропускает → entry point | `401` | то же |
| `AccessDeniedException` | `JsonAccessDeniedHandler` | `403` | `{"id":"<uuid>","code":"FORBIDDEN","message":"Forbidden","fieldErrors":null}` |
| Неверные credentials на login | `GlobalExceptionHandler` | `401` | `code: INVALID_CREDENTIALS` |
| Невалидный refresh token | `GlobalExceptionHandler` | `401` | `code: INVALID_REFRESH_TOKEN` |
| Отозванный refresh token | `GlobalExceptionHandler` | `403` | `code: REFRESH_TOKEN_REVOKED` |
| Чужой refresh token | `GlobalExceptionHandler` | `403` | `code: REFRESH_TOKEN_OWNERSHIP_ERROR` |

**[CONFIRMED]** `JsonAccessDeniedHandler` практически недостижим: `AccessDeniedException` возникает при провале authorization-правила, а единственное правило — `authenticated()`, которое даёт `401`. Реальные `403` приходят из `GlobalExceptionHandler` (revoked / ownership).

**[CONFIRMED]** Все сообщения `401` идентичны — клиент не может отличить «токен истёк» от «токен подделан».

---

## 16. Логирование чувствительных данных

**[CONFIRMED][RISK] HIGH.** `HttpLoggingFilter` (`@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`) логирует на уровне `INFO`:

- метод, URL с query-строкой;
- **тело запроса** (кроме `multipart/`, `application/octet-stream`, `image/`, `video/`), обрезанное до 1000 символов;
- статус и **тело ответа** по тем же правилам.

Что попадает в лог фактически:

| Запрос | Что утекает |
| --- | --- |
| `POST /auth/login` | **пароль в открытом виде** (тело) и **оба JWT** (ответ) |
| `POST /auth/refresh-token` | refresh token в теле, новый access token в ответе |
| `POST /auth/logout*` | refresh token |
| `POST /auth/register` | **пароль в открытом виде** |
| `POST /auth/password/reset/confirm` | **новый пароль и код сброса** |
| `POST /auth/password/reset/request?email=` | email в query-строке URL |
| `GET /auth/register/confirm?code=` | код активации в URL |
| `GET /files/{id}` | метаданные, включая **GPS-координаты** |

Уровень `ru.tbcarus.photocloudserver: INFO` в `application.yml` означает, что фильтр активен в стандартной конфигурации.

**[CONFIRMED]** В тестах маскирование реализовано (`AbstractIntegrationTest.maskSensitiveJson()` скрывает `accessToken`/`refreshToken`, `maskAuthorization()` обрезает Bearer), но **в production-фильтре маскирования нет**.

**[CONFIRMED]** `EmailService.sendEmail()` дополнительно логирует на INFO весь `EmailContext` и отрендеренный HTML письма — то есть **ссылку с кодом активации/сброса пароля**.

**[CONFIRMED]** `spring.jpa.show-sql: true` в `application.yml` — SQL-запросы с параметрами уходят в stdout.

---

## 17. Транспорт и заголовки

**[CONFIRMED]**

- HTTPS не настроен: `server.port: 8080`, `server.ssl.*` отсутствует. TLS предполагается на внешнем reverse-proxy (в репозитории его конфигурации нет).
- HSTS, `X-Content-Type-Options`, `X-Frame-Options`, CSP — используются значения Spring Security по умолчанию; явной настройки `headers()` нет.
- Дополнительной защиты от path traversal, кроме `StoragePathResolver`, нет — и она достаточна, так как клиент никогда не передаёт пути.

---

## 18. Фактические security boundaries (сводка)

```
┌──────────────────────────────────────────────────────────────────┐
│ ГРАНИЦА 1: аутентификация                                        │
│ permitAll-список (11 URL + springdoc)  |  всё остальное — JWT     │
│ Проверяется: подпись, token_type=ACCESS, exp, subject==username   │
│ НЕ проверяется: enabled, banned, отзыв access token               │
├──────────────────────────────────────────────────────────────────┤
│ ГРАНИЦА 2: владение объектом (ownership)                          │
│ Каждый lookup: WHERE id = ? AND user_id = ?                       │
│ Нарушение → 404 (не 403)                                          │
│ Ролевых проверок нет вообще                                       │
├──────────────────────────────────────────────────────────────────┤
│ ГРАНИЦА 3: файловая система                                       │
│ Клиент не передаёт путей; путь генерируется сервером               │
│ StoragePathResolver отбрасывает выход за storage.root             │
├──────────────────────────────────────────────────────────────────┤
│ ГРАНИЦА 4: refresh token                                          │
│ Существование в БД + revoked=false + token_type=REFRESH + exp     │
│ + владение (для logout)                                           │
│ НЕ проверяется: enabled/banned, ротация, replay                   │
└──────────────────────────────────────────────────────────────────┘
```

**Отсутствующие границы:** rate limiting, квоты, ограничение типов файлов, аудит-лог, IP-фильтрация, ограничение числа сессий, MFA.
