# Полная инвентаризация API

Все пути ниже имеют префикс /api/v1. [CONFIRMED] Это 36 явно объявленных controller mappings, включая 7 STUB с 501. Автоматические Spring MVC HEAD/OPTIONS, servlet error dispatch и инфраструктурные springdoc resources не считаются дополнительными бизнес-endpoint-ами.

Auth public означает отсутствие обязательного access JWT, но предъявленный некорректный/истёкший Bearer обрабатывается JWT filter **до** public route и может дать 401. Auth access: заголовок Authorization: Bearer <accessToken>, регистр/пробел префикса именно такой. Валидный refresh в header не аутентифицирует protected запрос. Все protected операции используют текущего пользователя, передать userId для смены владельца нельзя.

JSON запросы передаются с Content-Type application/json; multipart — с boundary и part file. На перечисленные custom ошибки распространяется ErrorResponse {id:UUID string,code:string,message:string,fieldErrors:object|null}. 501 заглушки используют только {message}, success confirm registration — строку, download — байты, DELETE — пустое тело. Общий универсальный JSON handler на все исключения отсутствует. Для IOException, некоторых binding errors и servlet/container errors нельзя обещать такой же envelope.

Ошибки авторизации могут предшествовать ошибкам DTO. Неправильный HTTP method, content type и несуществующий URI не имеют отдельного пользовательского handler; exact framework response не был runtime-проверен. Business controller returns перечислены ниже; error dispatch/proxy способен повлиять на wire response вне этих веток.

DTO поля полностью перечислены в client-handoff/04-media-data-contract.md. Numeric IDs — Java Long, время — LocalDateTime без offset, перечисления — uppercase. Политика retries в этих документах — [INFERRED] следствие side effects, а не реализованный серверный retry engine.

## Сводная таблица

| Method | URI | Controller.method | Auth | Реализация / success |
| --- | --- | --- | --- | --- |
| GET | /api/v1/test | RootController.testPermitAll | public | IMPLEMENTED / 200 |
| GET | /api/v1/test/auth | RootController.testAuth | access | IMPLEMENTED / 200 |
| POST | /api/v1/auth/register | RegisterController.register | public | IMPLEMENTED / 201 |
| GET | /api/v1/auth/register/confirm | RegisterController.verifyEmail | public | IMPLEMENTED / 200 |
| POST | /api/v1/auth/register/resend | RegisterController.resendVerifyEmail | public | STUB / 501 |
| POST | /api/v1/auth/login | AuthController.login | public | IMPLEMENTED / 200 |
| POST | /api/v1/auth/refresh-token | AuthController.refresh | public | IMPLEMENTED / 200 |
| POST | /api/v1/auth/logout | AuthController.logout | access | IMPLEMENTED / 200 |
| POST | /api/v1/auth/logout-all | AuthController.logoutAll | access | IMPLEMENTED / 200 |
| POST | /api/v1/auth/logout-others | AuthController.logoutOthers | access | IMPLEMENTED / 200 |
| POST | /api/v1/auth/password/reset/request | PasswordController.forgotPassword | public | IMPLEMENTED / 200 |
| POST | /api/v1/auth/password/reset/confirm | PasswordController.resetPassword | public | IMPLEMENTED / 200 |
| POST | /api/v1/auth/password/reset/resend | PasswordController.resendResetPassword | public | STUB / 501 |
| GET | /api/v1/auth/password/reset/page | PasswordController.getResetPasswordPage | public | STUB / 501 |
| POST | /api/v1/auth/password/reset/page | PasswordController.submitResetPasswordPage | public | STUB / 501 |
| GET | /api/v1/profile | UserController.getProfile | access | IMPLEMENTED / 200 |
| PATCH | /api/v1/profile | UserController.updateProfile | access | STUB / 501 |
| GET | /api/v1/profile/settings | UserController.getSettings | access | STUB / 501 |
| PATCH | /api/v1/profile/settings | UserController.updateSettings | access | STUB / 501 |
| POST | /api/v1/files | FileController.uploadFile | access | IMPLEMENTED / 200 |
| POST | /api/v1/files/upload | FileController.uploadFileExplicit | access | IMPLEMENTED / 200 |
| GET | /api/v1/files | FileController.getUserFiles | access | IMPLEMENTED / 200 |
| GET | /api/v1/files/{id} | FileController.getFile | access | IMPLEMENTED / 200 |
| GET | /api/v1/files/{id}/download | FileController.downloadFile | access | IMPLEMENTED / 200 |
| PATCH | /api/v1/files/{id} | FileController.renameFile | access | IMPLEMENTED / 200 |
| POST | /api/v1/files/{id}/move | FileController.moveFile | access | IMPLEMENTED / 200 |
| POST | /api/v1/files/{id}/copy | FileController.copyFile | access | IMPLEMENTED / 200 |
| DELETE | /api/v1/files/{id} | FileController.deleteFile | access | IMPLEMENTED / 204 |
| GET | /api/v1/files/checksums | FileController.getChecksums | access | IMPLEMENTED / 200 |
| POST | /api/v1/files/checksums/exists | FileController.checkExistingChecksums | access | IMPLEMENTED / 200 |
| GET | /api/v1/folders/root | FolderController.getRoot | access | IMPLEMENTED / 200 |
| GET | /api/v1/folders/{id}/children | FolderController.getChildren | access | IMPLEMENTED / 200 |
| POST | /api/v1/folders | FolderController.createFolder | access | IMPLEMENTED / 200 |
| PATCH | /api/v1/folders/{id} | FolderController.renameFolder | access | IMPLEMENTED / 200 |
| POST | /api/v1/folders/{id}/move | FolderController.moveFolder | access | IMPLEMENTED / 200 |
| DELETE | /api/v1/folders/{id} | FolderController.deleteFolder | access | IMPLEMENTED / 204 |

## 01. GET /api/v1/test

[CONFIRMED] Auth: public.

**Request / validation:** Нет параметров/body.

**Response:** 200 JSON {message: 'All good! Permit all connection'}.

**Errors:** Общие ошибки JWT-filter при переданном Bearer.

**Побочные эффекты / БД / storage:** Нет бизнес-записей.

**Повтор / идемпотентность:** Повтор безопасен.

**Источник:** RootController.testPermitAll(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 02. GET /api/v1/test/auth

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 JSON {message: 'All good! Authenticated connection. Hello <email>'}.

**Errors:** 401 UNAUTHORIZED.

**Побочные эффекты / БД / storage:** Только загрузка User для аутентификации.

**Повтор / идемпотентность:** Повтор безопасен.

**Источник:** RootController.testAuth(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest.protectedEndpointWithoutTokenReturnsUnauthorizedErrorResponse; happy — smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 03. POST /api/v1/auth/register

[CONFIRMED] Auth: public.

**Request / validation:** JSON RegisterRequest: email String обязательный @NotBlank/@Email; password String обязательный @NotBlank, 4..20. displayName не входит в DTO.

**Response:** 201 JSON {message: 'Email was sent'}.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST; 409 CONFLICT email; гонка unique email → 500 DATABASE_CONSTRAINT_VIOLATION; SMTP runtime error не нормализован.

**Побочные эффекты / БД / storage:** INSERT users и user_roles(USER), INSERT email_requests(ACTIVATE), синхронная попытка SMTP. Нет общей транзакции.

**Повтор / идемпотентность:** Не идемпотентен: повтор после сохранения user даёт 409; доставку письма по этому статусу определить нельзя.

**Источник:** RegisterController.register(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest: validation, duplicate; successful register+SMTP не покрыт. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 04. GET /api/v1/auth/register/confirm

[CONFIRMED] Auth: public.

**Request / validation:** Query code String обязателен; @NotBlank/UUID-format нет.

**Response:** 200 строка User <email> was verified; это не JSON message envelope.

**Errors:** 400 BAD_REQUEST missing param; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code.

**Побочные эффекты / БД / storage:** В транзакции enabled=true и used=true у предъявленного кода.

**Повтор / идемпотентность:** Меняет состояние: повтор consumed code → 400.

**Источник:** RegisterController.verifyEmail(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest: invalidRegistrationCode..., usedRegistrationCode..., expiredRegistrationCode...; happy confirmation не найден. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 05. POST /api/v1/auth/register/resend

[CONFIRMED] Auth: public.

**Request / validation:** Нет принимаемого DTO/параметров.

**Response:** 501 JSON {message: 'Registration confirmation resend is not implemented yet'}.

**Errors:** 501 — тело с message, без ErrorResponse.

**Побочные эффекты / БД / storage:** STUB; нет записи/SMTP.

**Повтор / идемпотентность:** Повтор не реализует resend.

**Источник:** RegisterController.resendVerifyEmail(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 06. POST /api/v1/auth/login

[CONFIRMED] Auth: public.

**Request / validation:** JSON LoginRequest: email @NotBlank/@Email; password @NotBlank, 4..20.

**Response:** 200 LoginResponse {accessToken:String, refreshToken:String}.

**Errors:** 400 validation/body; 401 INVALID_CREDENTIALS: unknown/wrong password/disabled/banned.

**Побочные эффекты / БД / storage:** UPDATE lastLoginAt (также lastUpdate); INSERT refresh_token; выдача access JWT.

**Повтор / идемпотентность:** Каждый успешный повтор создаёт новый refresh с jti; не идемпотентен.

**Источник:** AuthController.login(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest; helper loginAndGetAccessToken всех integration tests. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 07. POST /api/v1/auth/refresh-token

[CONFIRMED] Auth: public.

**Request / validation:** JSON RefreshRequest {refreshToken:String @NotBlank}. Access header не требуется.

**Response:** 200 RefreshResponse {accessToken:String}; refreshToken в ответе отсутствует.

**Errors:** 400 validation/body; 401 INVALID_REFRESH_TOKEN: DB lookup/parse/type/expiration/subject/user; 403 REFRESH_TOKEN_REVOKED: revoked row.

**Побочные эффекты / БД / storage:** Чтение refresh row и User, новый access. Нет refresh rotation, UPDATE expires или проверки enabled/banned.

**Повтор / идемпотентность:** Повтор допустим, создаёт access; refresh остаётся прежним. Не возвращает гарантированно те же байты access.

**Источник:** AuthController.refresh(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest: blank/unknown/revoked; happy/expired signed token не покрыт. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 08. POST /api/v1/auth/logout

[CONFIRMED] Auth: access.

**Request / validation:** JSON LogoutRequest {refreshToken:String @NotBlank}; token должен быть записан на email текущего User.

**Response:** 200 JSON {message: 'Logged out successfully'}.

**Errors:** 400 validation; 401 UNAUTHORIZED; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.

**Побочные эффекты / БД / storage:** revoked=true, revokedAt=now для одной строки; access не отзывается.

**Повтор / идемпотентность:** Повтор своего существующего токена — 200, снова меняет revokedAt. JWT exp предъявленного refresh не проверяется.

**Источник:** AuthController.logout(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest: own, foreign, unknown, blank. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 09. POST /api/v1/auth/logout-all

[CONFIRMED] Auth: access.

**Request / validation:** Body/параметры не нужны.

**Response:** 200 JSON {message: 'All logged out successfully'}.

**Errors:** 401 UNAUTHORIZED; необработанные DB errors.

**Побочные эффекты / БД / storage:** Все строки email с revoked=false → true, включая expired; revokedAt не заполняется.

**Повтор / идемпотентность:** Повтор без новых logins ничего не меняет; новый login после logout-all не заблокирован.

**Источник:** AuthController.logoutAll(), DTO из model/dto; соответствующий service. **Тесты:** JUnit endpoint-вызов не найден; smoke есть. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 10. POST /api/v1/auth/logout-others

[CONFIRMED] Auth: access.

**Request / validation:** JSON LogoutRequest {refreshToken:String @NotBlank}; исключаемая запись должна принадлежать User.

**Response:** 200 JSON {message: 'All other logged out successfully'}.

**Errors:** 400 validation; 401 UNAUTHORIZED; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.

**Побочные эффекты / БД / storage:** У остальных строк email с revoked=false → true. Сам переданный token может быть уже revoked/expired; проверяется ownership, не активность.

**Повтор / идемпотентность:** Повтор без новых сессий не меняет набор; access-токены продолжают действовать.

**Источник:** AuthController.logoutOthers(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest: own, foreign, unknown. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 11. POST /api/v1/auth/password/reset/request

[CONFIRMED] Auth: public.

**Request / validation:** Request parameter email:String @NotBlank (обычно query; form request parameter также читается). @Email нет; JSON {email} не является контрактом.

**Response:** 200 JSON {message: 'Email was sent'}.

**Errors:** 400 VALIDATION_ERROR blank; 400 BAD_REQUEST missing/user unknown; SMTP exception может уйти без ErrorResponse.

**Побочные эффекты / БД / storage:** INSERT PASSWORD_RESET email_requests, синхронное письмо; лимит checkAndGenerateCode не вызывается.

**Повтор / идемпотентность:** Не идемпотентен: новый код и новая попытка письма при каждом вызове.

**Источник:** PasswordController.forgotPassword(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest.passwordResetRequestValidationRejectsBlankEmail; SMTP happy не покрыт. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 12. POST /api/v1/auth/password/reset/confirm

[CONFIRMED] Auth: public.

**Request / validation:** JSON PasswordResetConfirmRequest {password:String @NotBlank 4..20, code:String @NotBlank}. Старые query-only password/code не поддержаны.

**Response:** 200 JSON {message: 'Password was reset'}.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code.

**Побочные эффекты / БД / storage:** В транзакции BCrypt password; code и PASSWORD_RESET codes последних 3 дней → used. Refresh/access не отзываются.

**Повтор / идемпотентность:** Повтор использованного code → 400; после потери ответа нельзя считать 400 доказательством неуспеха первого запроса.

**Источник:** PasswordController.resetPassword(), DTO из model/dto; соответствующий service. **Тесты:** AuthErrorHandlingIntegrationTest.successfulPasswordReset и negative validation/legacy-query tests. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 13. POST /api/v1/auth/password/reset/resend

[CONFIRMED] Auth: public.

**Request / validation:** Нет принимаемого DTO/параметров.

**Response:** 501 JSON {message: 'Password reset resend is not implemented yet'}.

**Errors:** 501 message-only.

**Побочные эффекты / БД / storage:** STUB; нет SMTP/БД.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

**Источник:** PasswordController.resendResetPassword(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 14. GET /api/v1/auth/password/reset/page

[CONFIRMED] Auth: public.

**Request / validation:** Query code:String обязателен, но при наличии не проверяется.

**Response:** 501 JSON {message: 'Password reset page is not implemented yet'}; HTML не возвращается.

**Errors:** 400 BAD_REQUEST если нет code; иначе 501.

**Побочные эффекты / БД / storage:** STUB; сюда ведёт reset-email link.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

**Источник:** PasswordController.getResetPasswordPage(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 15. POST /api/v1/auth/password/reset/page

[CONFIRMED] Auth: public.

**Request / validation:** Нет объявленного body/параметров формы.

**Response:** 501 JSON {message: 'Password reset page submit is not implemented yet'}.

**Errors:** 501 message-only.

**Побочные эффекты / БД / storage:** STUB; пароль не меняет.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

**Источник:** PasswordController.submitResetPasswordPage(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 16. GET /api/v1/profile

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 UserDto: id,email,displayName,enabled,banned,roles,createdAt,lastUpdate,lastLoginAt.

**Errors:** 401 UNAUTHORIZED.

**Побочные эффекты / БД / storage:** Нет изменения; DTO из User principal, предварительно загруженного из БД.

**Повтор / идемпотентность:** Повтор безопасен.

**Источник:** UserController.getProfile(), DTO из model/dto; соответствующий service. **Тесты:** ProfileIntegrationTest.profileUsesCurrentUserFields. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 17. PATCH /api/v1/profile

[CONFIRMED] Auth: access.

**Request / validation:** DTO не определён; присланные поля не обрабатываются.

**Response:** 501 JSON {message: 'Profile update is not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Побочные эффекты / БД / storage:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

**Источник:** UserController.updateProfile(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 18. GET /api/v1/profile/settings

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров.

**Response:** 501 JSON {message: 'Profile settings are not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Побочные эффекты / БД / storage:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

**Источник:** UserController.getSettings(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 19. PATCH /api/v1/profile/settings

[CONFIRMED] Auth: access.

**Request / validation:** DTO не определён; autoUploadEnabled из smoke не обрабатывается.

**Response:** 501 JSON {message: 'Profile settings update is not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Побочные эффекты / БД / storage:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

**Источник:** UserController.updateSettings(), DTO из model/dto; соответствующий service. **Тесты:** Только ручной smoke. **Документация:** docs/api-user-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 20. POST /api/v1/files

[CONFIRMED] Auth: access.

**Request / validation:** multipart/form-data: file MultipartFile обязателен; folderId Long optional request parameter. Файл непустой, серверный stream limit 104857600 байт; multipart file/request 110MB. Клиентский checksum, MIME override, capturedAt и status не принимаются.

**Response:** 200 FileItemDto, в том числе для duplicate. Новая запись не обозначается 201.

**Errors:** 400 BAD_REQUEST empty/argument; 400 на неверный/missing multipart binding, форма не вся покрыта custom handler; 401; 404 NOT_FOUND folder; 409 CONFLICT name; 413 FILE_TOO_LARGE; 500 constraint без найденного raced duplicate; IOException body не унифицирован.

**Побочные эффекты / БД / storage:** Temp → SHA/size/MIME/metadata → default folder при необходимости → duplicate check → filename check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate — только temp cleanup и старый DTO.

**Повтор / идемпотентность:** Одинаковые байты + User + folder → существующий ID, пока он остаётся в этой папке. Новое имя duplicate игнорируется. Другой folder → новая физическая запись.

**Источник:** FileController.uploadFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest: new/duplicate/different-folder/limit/ownership. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 21. POST /api/v1/files/upload

[CONFIRMED] Auth: access.

**Request / validation:** multipart/form-data: file MultipartFile обязателен; folderId Long optional request parameter. Файл непустой, серверный stream limit 104857600 байт; multipart file/request 110MB. Клиентский checksum, MIME override, capturedAt и status не принимаются.

**Response:** 200 FileItemDto, в том числе для duplicate. Новая запись не обозначается 201.

**Errors:** 400 BAD_REQUEST empty/argument; 400 на неверный/missing multipart binding, форма не вся покрыта custom handler; 401; 404 NOT_FOUND folder; 409 CONFLICT name; 413 FILE_TOO_LARGE; 500 constraint без найденного raced duplicate; IOException body не унифицирован.

**Побочные эффекты / БД / storage:** Temp → SHA/size/MIME/metadata → default folder при необходимости → duplicate check → filename check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate — только temp cleanup и старый DTO.

**Повтор / идемпотентность:** Одинаковые байты + User + folder → существующий ID, пока он остаётся в этой папке. Новое имя duplicate игнорируется. Другой folder → новая физическая запись.

**Источник:** FileController.uploadFileExplicit(), DTO из model/dto; соответствующий service. **Тесты:** Отдельного JUnit/ручного smoke вызова не найдено; общий pipeline проверяется через POST /files. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 22. GET /api/v1/files

[CONFIRMED] Auth: access.

**Request / validation:** Query page:int=0, size:int=10, folderId:Long optional. PageRequest требует page>=0,size>0; верхний размер не ограничен. Нет параметров sort/type/from/to/search.

**Response:** 200 PageResponse<FileItemDto>: items,page,size,totalElements,totalPages,hasNext,hasPrevious. Sort capturedAt DESC, uploadedAt DESC,id DESC; folderId — только прямые файлы; без него все папки.

**Errors:** 400 BAD_REQUEST отрицательная page/неположительная size; ошибки преобразования типов могут иметь framework body; 401; 404 NOT_FOUND folder.

**Побочные эффекты / БД / storage:** Read files/count, не пишет БД/storage.

**Повтор / идемпотентность:** Read repeat безопасен, но offset pages не фиксируют snapshot при изменениях.

**Источник:** FileController.getUserFiles(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest: list scopes, pagination, sort. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 23. GET /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long, обязательный; проверка positive отсутствует.

**Response:** 200 FileItemDto, metadata может быть null.

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND для missing/foreign; invalid Long — framework binding.

**Побочные эффекты / БД / storage:** Чтение owned FileItem с EntityGraph; физический файл не проверяется.

**Повтор / идемпотентность:** Read repeat безопасен.

**Источник:** FileController.getFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest.metadataReturnsCurrentUserFile и foreign/missing tests. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 24. GET /api/v1/files/{id}/download

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; собственные Range/ETag/query-параметры не объявлены.

**Response:** 200 raw Resource bytes; Content-Type из detectedMimeType; Content-Disposition: attachment; filename="<originalName>".

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND для missing/foreign и !exists/!isReadable; поздняя IOException не имеет custom mapping.

**Побочные эффекты / БД / storage:** Чтение файла через UrlResource; checksum/size не пересчитывается.

**Повтор / идемпотентность:** Read repeat безопасен; byte-range/resume поведение MVC не тестировалось, 206 не объявлен как собственная реализация.

**Источник:** FileController.downloadFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest.downloadReturnsOriginalBytesAndContentType, foreignMissingAndMissingPhysicalFilesReturnNotFound. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 25. PATCH /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON RenameFileRequest {originalName:String @NotBlank,@Size(max=255)}. Service trim + sanitizer; wire response originalFilename.

**Response:** 200 FileItemDto.

**Errors:** 400 validation/argument; 401; 404 FILE_ITEM_NOT_FOUND; 409 CONFLICT name outside CAMERA; DB errors.

**Побочные эффекты / БД / storage:** UPDATE file_item.original_name; physical filename/content unchanged.

**Повтор / идемпотентность:** Повтор одинакового имени обычно 200; состояние других файлов может дать конфликт.

**Источник:** FileController.renameFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest: renameChangesOnlyOriginalName..., renameConflictIsRejectedOutsideCamera. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 26. POST /api/v1/files/{id}/move

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON MoveFileRequest {targetFolderId:Long @NotNull}, папка текущего пользователя любого типа.

**Response:** 200 FileItemDto.

**Errors:** 400 validation; 401; 404 FILE_ITEM_NOT_FOUND/NOT_FOUND; 409 CONFLICT name outside CAMERA; 500 DATABASE_CONSTRAINT_VIOLATION при duplicate checksum с прошедшей name check.

**Побочные эффекты / БД / storage:** UPDATE file_item.folder_id; physical path неизменен. Предварительной checksum check нет.

**Повтор / идемпотентность:** Повтор той же target folder обычно 200; при потере ответа сверить GET card. Конфликт checksum сам retry не исправит.

**Источник:** FileController.moveFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest.moveChangesOnlyFolder... и foreignFolderCannotBeUsed...; checksum conflict move не покрыт. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 27. POST /api/v1/files/{id}/copy

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON CopyFileRequest обязателен, {} допустимо: targetFolderId optional, originalName optional @Size(max=255). Null/blank name → source name; no target → source folder.

**Response:** 200 новый FileItemDto.

**Errors:** 400 validation/body; 401; 404 source/target missing/foreign; 409 имя либо checksum; 500 constraint race; missing physical source → IOException без специального 404 handler.

**Побочные эффекты / БД / storage:** Files.copy → transaction INSERT новый StoredObject/FileItem, clone metadata; uploadedAt новый, capturedAt прежний. Same-folder copy конфликтует по checksum даже с новым именем.

**Повтор / идемпотентность:** Не идемпотентен по ответу: успешный copy + повтор → 409. Для разбора неизвестного исхода нужен список целевой папки.

**Источник:** FileController.copyFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest: copy physical independence, same-folder name/checksum conflicts, foreign target. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 28. DELETE /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; body/query не используются.

**Response:** 204 без body.

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND; DB errors. IOException удаления файла логируется и не меняет 204.

**Побочные эффекты / БД / storage:** Owner: transaction delete all FileItems на SO + metadata + SO; затем physical delete. Nonowner of SO: только логическая запись и её metadata.

**Повтор / идемпотентность:** По состоянию повтор безопасен, но после успеха 404; 204 не доказывает physical deletion.

**Источник:** FileController.deleteFile(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest: delete, nonOwnerDelete..., foreign/missing. ownerDelete... после idempotent upload фактически проверяет одну уникальную запись. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 29. GET /api/v1/files/checksums

[CONFIRMED] Auth: access.

**Request / validation:** Нет объявленных параметров; folderId/page не применяются.

**Response:** 200 List<FileChecksumDto>: id,originalFilename,checksum. Без folderId; один item на FileItem, checksum из SO; одинаковый checksum может повторяться.

**Errors:** 401; DB/framework errors.

**Побочные эффекты / БД / storage:** JPQL projection всех файлов пользователя; без проверки диска.

**Повтор / идемпотентность:** Read repeat безопасен; нет pagination/snapshot.

**Источник:** FileController.getChecksums(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest.checksumsReturnsOnlyCurrentUserChecksumsWithIds; smoke. **Документация:** docs/api-file-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 30. POST /api/v1/files/checksums/exists

[CONFIRMED] Auth: access.

**Request / validation:** JSON ChecksumExistsRequest {folderId:Long @NotNull, checksums:List<String> @NotEmpty}; каждый @NotBlank + ^[0-9a-fA-F]{64}$. Max input list=500 **до** dedup.

**Response:** 200 ChecksumExistsResponse {existing:String[],missing:String[]}; lowercase, уникально, порядок первого появления внутри каждой группы.

**Errors:** 400 VALIDATION_ERROR либо BAD_REQUEST over max; 401; 404 NOT_FOUND target folder.

**Побочные эффекты / БД / storage:** Read-only user+folder+lower(file_item.checksum); ни INSERT, ни disk check.

**Повтор / идемпотентность:** Read repeat безопасен; existing не является резервированием или подтверждением доступности байтов.

**Источник:** FileController.checkExistingChecksums(), DTO из model/dto; соответствующий service. **Тесты:** FileFlowIntegrationTest checksumExists* (7); ChecksumSyncServiceTest (2). **Документация:** docs/api-checksum-sync-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 31. GET /api/v1/folders/root

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 FolderDto ROOT {id,parentId:null,name:'root',folderType:'ROOT',createdAt,updatedAt}.

**Errors:** 401; DB/race errors не унифицированы в 409.

**Побочные эффекты / БД / storage:** Читает или INSERT ROOT; Camera/Files не создаёт.

**Повтор / идемпотентность:** Повтор возвращает ту же папку при неизменном состоянии, первый GET пишет БД.

**Источник:** FolderController.getRoot(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest.rootIsCreatedOnlyOnce (последовательный, не race test). **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 32. GET /api/v1/folders/{id}/children

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; pagination/recursive flag нет.

**Response:** 200 List<FolderDto> прямых children; ORDER BY lower(name),id. Пустая папка → [].

**Errors:** 401; 404 NOT_FOUND parent missing/foreign.

**Побочные эффекты / БД / storage:** Только чтение.

**Повтор / идемпотентность:** Read repeat безопасен.

**Источник:** FolderController.getChildren(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest.childrenReturnsOnlyCurrentUserFolders, cannotWorkWithForeignFolder. **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 33. POST /api/v1/folders

[CONFIRMED] Auth: access.

**Request / validation:** JSON CreateFolderRequest {parentId:Long optional,name:String @NotBlank,@Size(max=255)}. Service trim; no parent → root; parent должен ROOT/USER; Camera/Files names в root зарезервированы.

**Response:** 200 FolderDto нового USER.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST system leaf/blank; 401; 404 NOT_FOUND; 409 CONFLICT name/reserved; конкурентный DB failure может выйти 500.

**Побочные эффекты / БД / storage:** INSERT USER folder; возможно lazy ROOT. Physical directory не создаётся.

**Повтор / идемпотентность:** Не идемпотентен: повтор name+parent → 409.

**Источник:** FolderController.createFolder(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest: create helpers, duplicate names, system leaf, reserved names, different parents. **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 34. PATCH /api/v1/folders/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON RenameFolderRequest {name:String @NotBlank,@Size(max=255)}; trim; только USER.

**Response:** 200 FolderDto.

**Errors:** 400 system folder/validation; 401; 404 NOT_FOUND; 409 CONFLICT duplicate/reserved; поздняя constraint race может стать 500.

**Побочные эффекты / БД / storage:** UPDATE name и updatedAt; disk unchanged.

**Повтор / идемпотентность:** Повтор того же имени обычно допустим.

**Источник:** FolderController.renameFolder(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest.cannotRenameRootCameraOrFiles проверяет ROOT/CAMERA, не FILES; happy rename отдельно не найден. **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 35. POST /api/v1/folders/{id}/move

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON MoveFolderRequest {targetParentId:Long @NotNull}. USER → ROOT/USER; не self/descendant; ownership обоих IDs.

**Response:** 200 FolderDto.

**Errors:** 400 system folder/leaf/self/descendant/validation; 401; 404 NOT_FOUND; 409 CONFLICT target name/reserved; DB failures.

**Побочные эффекты / БД / storage:** UPDATE parent_id/updatedAt; descendants через parent chain остаются связаны; файлы физически не перемещаются.

**Повтор / идемпотентность:** Повтор той же target обычно допустим; concurrent graph changes отдельно не сериализованы.

**Источник:** FolderController.moveFolder(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest: system/self/descendant/foreign negatives; happy move не найден. **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## 36. DELETE /api/v1/folders/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; только empty USER: без child folders и file_items.

**Response:** 204 без body.

**Errors:** 400 BAD_REQUEST system/nonempty; 401; 404 NOT_FOUND; concurrent FK error возможен как 500.

**Побочные эффекты / БД / storage:** DELETE folder row; нет physical storage операций.

**Повтор / идемпотентность:** Повтор после успеха →404; по состоянию идемпотентен.

**Источник:** FolderController.deleteFolder(), DTO из model/dto; соответствующий service. **Тесты:** FolderApiIntegrationTest.deleteOnlyEmptyUserFolder, cannotDeleteRootCameraOrFiles. **Документация:** docs/api-folder-contract.md; для /files/upload отдельного smoke нет. Фактический вызов Android-клиентом не установлен.

## Legacy и инфраструктура

[CONFIRMED] POST /files — рабочий compatibility alias, не удалённый маршрут (FileController comment и два вызова одного upload pipeline). [DOCUMENTED] GET /files/checksums назван старым в api-file-contract.md, но endpoint работает и проверяется integration tests. Нельзя помечать их [UNUSED] только из-за наличия нового API.

[CONFIRMED — config/build] Springdoc зависимость подключена; /swagger-ui/**, /swagger-resources/*, /v3/api-docs/** разрешены SecurityConfig. Runtime OpenAPI document не выгружался. Контроллеры имеют @Operation summaries и @Tag, но полный security scheme/спецификация всех error responses не заданы. /swagger-ui.html отдельно в whitelist не указан. Собственного /health или Actuator не найдено.

## API contract inconsistencies

Файла с точным именем api-contract.md не найдено. Сверены api-user-contract.md, api-file-contract.md, api-folder-contract.md, api-checksum-sync-contract.md, application-overview.md, README.md, README-description.md, TODO.txt, http-tests.

[INCONSISTENCY] Основные подтверждённые различия: move checksum conflict не описан как 500; TODO в file contract говорит о будущем server-side copy при существующем /{id}/copy; общий error JSON обещан шире реально обработанных exception types; email error поведение зависит от checked MessagingException против runtime MailException; smoke delete описывает обратный порядок disk/DB и старый storageKey; smoke reset намекает на отзыв токенов, которого нет. README относит папки к будущему, хотя CRUD реализован. Подробная таблица с severity: client-handoff/07-api-contract-differences.md.

[CONFIRMED] Batch folder+checksum contract, lowercase/dedup/order, DTO names, текущие auth TTL, JSON password-reset-confirm и список 501 в основных api-*-contract.md в целом соответствуют кодовым happy paths. Расхождения не означают, что вся документация устарела.

