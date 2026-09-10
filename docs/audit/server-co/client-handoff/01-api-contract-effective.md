# Фактический API-контракт Android ↔ Server

Срез: c2e9593a30270fddd2fb80d3f2d6ff0c533d2d37, аудит 2026-09-10. [CONFIRMED] Контракт восстановлен статически; это не результат запросов к развёрнутому серверу. Все 8 документов этого каталога можно передавать отдельно от серверных исходников.

Все пути ниже имеют префикс /api/v1. [CONFIRMED] Это 36 явно объявленных controller mappings, включая 7 STUB с 501. Автоматические Spring MVC HEAD/OPTIONS, servlet error dispatch и инфраструктурные springdoc resources не считаются дополнительными бизнес-endpoint-ами.

Auth public означает отсутствие обязательного access JWT, но предъявленный некорректный/истёкший Bearer обрабатывается JWT filter **до** public route и может дать 401. Auth access: заголовок Authorization: Bearer <accessToken>, регистр/пробел префикса именно такой. Валидный refresh в header не аутентифицирует protected запрос. Все protected операции используют текущего пользователя, передать userId для смены владельца нельзя.

JSON запросы передаются с Content-Type application/json; multipart — с boundary и part file. На перечисленные custom ошибки распространяется ErrorResponse {id:UUID string,code:string,message:string,fieldErrors:object|null}. 501 заглушки используют только {message}, success confirm registration — строку, download — байты, DELETE — пустое тело. Общий универсальный JSON handler на все исключения отсутствует. Для IOException, некоторых binding errors и servlet/container errors нельзя обещать такой же envelope.

Ошибки авторизации могут предшествовать ошибкам DTO. Неправильный HTTP method, content type и несуществующий URI не имеют отдельного пользовательского handler; exact framework response не был runtime-проверен. Business controller returns перечислены ниже; error dispatch/proxy способен повлиять на wire response вне этих веток.

DTO поля полностью перечислены в 04-media-data-contract.md. Numeric IDs — Java Long, время — LocalDateTime без offset, перечисления — uppercase. Политика retries в этих документах — [INFERRED] следствие side effects, а не реализованный серверный retry engine.

## Все endpoints

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

**Эффект и ограничения:** Нет бизнес-записей.

**Повтор / идемпотентность:** Повтор безопасен.

## 02. GET /api/v1/test/auth

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 JSON {message: 'All good! Authenticated connection. Hello <email>'}.

**Errors:** 401 UNAUTHORIZED.

**Эффект и ограничения:** Только загрузка User для аутентификации.

**Повтор / идемпотентность:** Повтор безопасен.

## 03. POST /api/v1/auth/register

[CONFIRMED] Auth: public.

**Request / validation:** JSON RegisterRequest: email String обязательный @NotBlank/@Email; password String обязательный @NotBlank, 4..20. displayName не входит в DTO.

**Response:** 201 JSON {message: 'Email was sent'}.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST; 409 CONFLICT email; гонка unique email → 500 DATABASE_CONSTRAINT_VIOLATION; SMTP runtime error не нормализован.

**Эффект и ограничения:** INSERT users и user_roles(USER), INSERT email_requests(ACTIVATE), синхронная попытка SMTP. Нет общей транзакции.

**Повтор / идемпотентность:** Не идемпотентен: повтор после сохранения user даёт 409; доставку письма по этому статусу определить нельзя.

## 04. GET /api/v1/auth/register/confirm

[CONFIRMED] Auth: public.

**Request / validation:** Query code String обязателен; @NotBlank/UUID-format нет.

**Response:** 200 строка User <email> was verified; это не JSON message envelope.

**Errors:** 400 BAD_REQUEST missing param; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code.

**Эффект и ограничения:** В транзакции enabled=true и used=true у предъявленного кода.

**Повтор / идемпотентность:** Меняет состояние: повтор consumed code → 400.

## 05. POST /api/v1/auth/register/resend

[CONFIRMED] Auth: public.

**Request / validation:** Нет принимаемого DTO/параметров.

**Response:** 501 JSON {message: 'Registration confirmation resend is not implemented yet'}.

**Errors:** 501 — тело с message, без ErrorResponse.

**Эффект и ограничения:** STUB; нет записи/SMTP.

**Повтор / идемпотентность:** Повтор не реализует resend.

## 06. POST /api/v1/auth/login

[CONFIRMED] Auth: public.

**Request / validation:** JSON LoginRequest: email @NotBlank/@Email; password @NotBlank, 4..20.

**Response:** 200 LoginResponse {accessToken:String, refreshToken:String}.

**Errors:** 400 validation/body; 401 INVALID_CREDENTIALS: unknown/wrong password/disabled/banned.

**Эффект и ограничения:** UPDATE lastLoginAt (также lastUpdate); INSERT refresh_token; выдача access JWT.

**Повтор / идемпотентность:** Каждый успешный повтор создаёт новый refresh с jti; не идемпотентен.

## 07. POST /api/v1/auth/refresh-token

[CONFIRMED] Auth: public.

**Request / validation:** JSON RefreshRequest {refreshToken:String @NotBlank}. Access header не требуется.

**Response:** 200 RefreshResponse {accessToken:String}; refreshToken в ответе отсутствует.

**Errors:** 400 validation/body; 401 INVALID_REFRESH_TOKEN: DB lookup/parse/type/expiration/subject/user; 403 REFRESH_TOKEN_REVOKED: revoked row.

**Эффект и ограничения:** Чтение refresh row и User, новый access. Нет refresh rotation, UPDATE expires или проверки enabled/banned.

**Повтор / идемпотентность:** Повтор допустим, создаёт access; refresh остаётся прежним. Не возвращает гарантированно те же байты access.

## 08. POST /api/v1/auth/logout

[CONFIRMED] Auth: access.

**Request / validation:** JSON LogoutRequest {refreshToken:String @NotBlank}; token должен быть записан на email текущего User.

**Response:** 200 JSON {message: 'Logged out successfully'}.

**Errors:** 400 validation; 401 UNAUTHORIZED; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.

**Эффект и ограничения:** revoked=true, revokedAt=now для одной строки; access не отзывается.

**Повтор / идемпотентность:** Повтор своего существующего токена — 200, снова меняет revokedAt. JWT exp предъявленного refresh не проверяется.

## 09. POST /api/v1/auth/logout-all

[CONFIRMED] Auth: access.

**Request / validation:** Body/параметры не нужны.

**Response:** 200 JSON {message: 'All logged out successfully'}.

**Errors:** 401 UNAUTHORIZED; необработанные DB errors.

**Эффект и ограничения:** Все строки email с revoked=false → true, включая expired; revokedAt не заполняется.

**Повтор / идемпотентность:** Повтор без новых logins ничего не меняет; новый login после logout-all не заблокирован.

## 10. POST /api/v1/auth/logout-others

[CONFIRMED] Auth: access.

**Request / validation:** JSON LogoutRequest {refreshToken:String @NotBlank}; исключаемая запись должна принадлежать User.

**Response:** 200 JSON {message: 'All other logged out successfully'}.

**Errors:** 400 validation; 401 UNAUTHORIZED; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR.

**Эффект и ограничения:** У остальных строк email с revoked=false → true. Сам переданный token может быть уже revoked/expired; проверяется ownership, не активность.

**Повтор / идемпотентность:** Повтор без новых сессий не меняет набор; access-токены продолжают действовать.

## 11. POST /api/v1/auth/password/reset/request

[CONFIRMED] Auth: public.

**Request / validation:** Request parameter email:String @NotBlank (обычно query; form request parameter также читается). @Email нет; JSON {email} не является контрактом.

**Response:** 200 JSON {message: 'Email was sent'}.

**Errors:** 400 VALIDATION_ERROR blank; 400 BAD_REQUEST missing/user unknown; SMTP exception может уйти без ErrorResponse.

**Эффект и ограничения:** INSERT PASSWORD_RESET email_requests, синхронное письмо; лимит checkAndGenerateCode не вызывается.

**Повтор / идемпотентность:** Не идемпотентен: новый код и новая попытка письма при каждом вызове.

## 12. POST /api/v1/auth/password/reset/confirm

[CONFIRMED] Auth: public.

**Request / validation:** JSON PasswordResetConfirmRequest {password:String @NotBlank 4..20, code:String @NotBlank}. Старые query-only password/code не поддержаны.

**Response:** 200 JSON {message: 'Password was reset'}.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code.

**Эффект и ограничения:** В транзакции BCrypt password; code и PASSWORD_RESET codes последних 3 дней → used. Refresh/access не отзываются.

**Повтор / идемпотентность:** Повтор использованного code → 400; после потери ответа нельзя считать 400 доказательством неуспеха первого запроса.

## 13. POST /api/v1/auth/password/reset/resend

[CONFIRMED] Auth: public.

**Request / validation:** Нет принимаемого DTO/параметров.

**Response:** 501 JSON {message: 'Password reset resend is not implemented yet'}.

**Errors:** 501 message-only.

**Эффект и ограничения:** STUB; нет SMTP/БД.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

## 14. GET /api/v1/auth/password/reset/page

[CONFIRMED] Auth: public.

**Request / validation:** Query code:String обязателен, но при наличии не проверяется.

**Response:** 501 JSON {message: 'Password reset page is not implemented yet'}; HTML не возвращается.

**Errors:** 400 BAD_REQUEST если нет code; иначе 501.

**Эффект и ограничения:** STUB; сюда ведёт reset-email link.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

## 15. POST /api/v1/auth/password/reset/page

[CONFIRMED] Auth: public.

**Request / validation:** Нет объявленного body/параметров формы.

**Response:** 501 JSON {message: 'Password reset page submit is not implemented yet'}.

**Errors:** 501 message-only.

**Эффект и ограничения:** STUB; пароль не меняет.

**Повтор / идемпотентность:** Retry не исправляет отсутствие реализации.

## 16. GET /api/v1/profile

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 UserDto: id,email,displayName,enabled,banned,roles,createdAt,lastUpdate,lastLoginAt.

**Errors:** 401 UNAUTHORIZED.

**Эффект и ограничения:** Нет изменения; DTO из User principal, предварительно загруженного из БД.

**Повтор / идемпотентность:** Повтор безопасен.

## 17. PATCH /api/v1/profile

[CONFIRMED] Auth: access.

**Request / validation:** DTO не определён; присланные поля не обрабатываются.

**Response:** 501 JSON {message: 'Profile update is not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Эффект и ограничения:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

## 18. GET /api/v1/profile/settings

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров.

**Response:** 501 JSON {message: 'Profile settings are not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Эффект и ограничения:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

## 19. PATCH /api/v1/profile/settings

[CONFIRMED] Auth: access.

**Request / validation:** DTO не определён; autoUploadEnabled из smoke не обрабатывается.

**Response:** 501 JSON {message: 'Profile settings update is not implemented yet'}.

**Errors:** 401 UNAUTHORIZED; 501.

**Эффект и ограничения:** STUB.

**Повтор / идемпотентность:** Retry бесполезен.

## 20. POST /api/v1/files

[CONFIRMED] Auth: access.

**Request / validation:** multipart/form-data: file MultipartFile обязателен; folderId Long optional request parameter. Файл непустой, серверный stream limit 104857600 байт; multipart file/request 110MB. Клиентский checksum, MIME override, capturedAt и status не принимаются.

**Response:** 200 FileItemDto, в том числе для duplicate. Новая запись не обозначается 201.

**Errors:** 400 BAD_REQUEST empty/argument; 400 на неверный/missing multipart binding, форма не вся покрыта custom handler; 401; 404 NOT_FOUND folder; 409 CONFLICT name; 413 FILE_TOO_LARGE; 500 constraint без найденного raced duplicate; IOException body не унифицирован.

**Эффект и ограничения:** Temp → SHA/size/MIME/metadata → default folder при необходимости → duplicate check → filename check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate — только temp cleanup и старый DTO.

**Повтор / идемпотентность:** Одинаковые байты + User + folder → существующий ID, пока он остаётся в этой папке. Новое имя duplicate игнорируется. Другой folder → новая физическая запись.

## 21. POST /api/v1/files/upload

[CONFIRMED] Auth: access.

**Request / validation:** multipart/form-data: file MultipartFile обязателен; folderId Long optional request parameter. Файл непустой, серверный stream limit 104857600 байт; multipart file/request 110MB. Клиентский checksum, MIME override, capturedAt и status не принимаются.

**Response:** 200 FileItemDto, в том числе для duplicate. Новая запись не обозначается 201.

**Errors:** 400 BAD_REQUEST empty/argument; 400 на неверный/missing multipart binding, форма не вся покрыта custom handler; 401; 404 NOT_FOUND folder; 409 CONFLICT name; 413 FILE_TOO_LARGE; 500 constraint без найденного raced duplicate; IOException body не унифицирован.

**Эффект и ограничения:** Temp → SHA/size/MIME/metadata → default folder при необходимости → duplicate check → filename check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate — только temp cleanup и старый DTO.

**Повтор / идемпотентность:** Одинаковые байты + User + folder → существующий ID, пока он остаётся в этой папке. Новое имя duplicate игнорируется. Другой folder → новая физическая запись.

## 22. GET /api/v1/files

[CONFIRMED] Auth: access.

**Request / validation:** Query page:int=0, size:int=10, folderId:Long optional. PageRequest требует page>=0,size>0; верхний размер не ограничен. Нет параметров sort/type/from/to/search.

**Response:** 200 PageResponse<FileItemDto>: items,page,size,totalElements,totalPages,hasNext,hasPrevious. Sort capturedAt DESC, uploadedAt DESC,id DESC; folderId — только прямые файлы; без него все папки.

**Errors:** 400 BAD_REQUEST отрицательная page/неположительная size; ошибки преобразования типов могут иметь framework body; 401; 404 NOT_FOUND folder.

**Эффект и ограничения:** Read files/count, не пишет БД/storage.

**Повтор / идемпотентность:** Read repeat безопасен, но offset pages не фиксируют snapshot при изменениях.

## 23. GET /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long, обязательный; проверка positive отсутствует.

**Response:** 200 FileItemDto, metadata может быть null.

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND для missing/foreign; invalid Long — framework binding.

**Эффект и ограничения:** Чтение owned FileItem с EntityGraph; физический файл не проверяется.

**Повтор / идемпотентность:** Read repeat безопасен.

## 24. GET /api/v1/files/{id}/download

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; собственные Range/ETag/query-параметры не объявлены.

**Response:** 200 raw Resource bytes; Content-Type из detectedMimeType; Content-Disposition: attachment; filename="<originalName>".

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND для missing/foreign и !exists/!isReadable; поздняя IOException не имеет custom mapping.

**Эффект и ограничения:** Чтение файла через UrlResource; checksum/size не пересчитывается.

**Повтор / идемпотентность:** Read repeat безопасен; byte-range/resume поведение MVC не тестировалось, 206 не объявлен как собственная реализация.

## 25. PATCH /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON RenameFileRequest {originalName:String @NotBlank,@Size(max=255)}. Service trim + sanitizer; wire response originalFilename.

**Response:** 200 FileItemDto.

**Errors:** 400 validation/argument; 401; 404 FILE_ITEM_NOT_FOUND; 409 CONFLICT name outside CAMERA; DB errors.

**Эффект и ограничения:** UPDATE file_item.original_name; physical filename/content unchanged.

**Повтор / идемпотентность:** Повтор одинакового имени обычно 200; состояние других файлов может дать конфликт.

## 26. POST /api/v1/files/{id}/move

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON MoveFileRequest {targetFolderId:Long @NotNull}, папка текущего пользователя любого типа.

**Response:** 200 FileItemDto.

**Errors:** 400 validation; 401; 404 FILE_ITEM_NOT_FOUND/NOT_FOUND; 409 CONFLICT name outside CAMERA; 500 DATABASE_CONSTRAINT_VIOLATION при duplicate checksum с прошедшей name check.

**Эффект и ограничения:** UPDATE file_item.folder_id; physical path неизменен. Предварительной checksum check нет.

**Повтор / идемпотентность:** Повтор той же target folder обычно 200; при потере ответа сверить GET card. Конфликт checksum сам retry не исправит.

## 27. POST /api/v1/files/{id}/copy

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON CopyFileRequest обязателен, {} допустимо: targetFolderId optional, originalName optional @Size(max=255). Null/blank name → source name; no target → source folder.

**Response:** 200 новый FileItemDto.

**Errors:** 400 validation/body; 401; 404 source/target missing/foreign; 409 имя либо checksum; 500 constraint race; missing physical source → IOException без специального 404 handler.

**Эффект и ограничения:** Files.copy → transaction INSERT новый StoredObject/FileItem, clone metadata; uploadedAt новый, capturedAt прежний. Same-folder copy конфликтует по checksum даже с новым именем.

**Повтор / идемпотентность:** Не идемпотентен по ответу: успешный copy + повтор → 409. Для разбора неизвестного исхода нужен список целевой папки.

## 28. DELETE /api/v1/files/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; body/query не используются.

**Response:** 204 без body.

**Errors:** 401; 404 FILE_ITEM_NOT_FOUND; DB errors. IOException удаления файла логируется и не меняет 204.

**Эффект и ограничения:** Owner: transaction delete all FileItems на SO + metadata + SO; затем physical delete. Nonowner of SO: только логическая запись и её metadata.

**Повтор / идемпотентность:** По состоянию повтор безопасен, но после успеха 404; 204 не доказывает physical deletion.

## 29. GET /api/v1/files/checksums

[CONFIRMED] Auth: access.

**Request / validation:** Нет объявленных параметров; folderId/page не применяются.

**Response:** 200 List<FileChecksumDto>: id,originalFilename,checksum. Без folderId; один item на FileItem, checksum из SO; одинаковый checksum может повторяться.

**Errors:** 401; DB/framework errors.

**Эффект и ограничения:** JPQL projection всех файлов пользователя; без проверки диска.

**Повтор / идемпотентность:** Read repeat безопасен; нет pagination/snapshot.

## 30. POST /api/v1/files/checksums/exists

[CONFIRMED] Auth: access.

**Request / validation:** JSON ChecksumExistsRequest {folderId:Long @NotNull, checksums:List<String> @NotEmpty}; каждый @NotBlank + ^[0-9a-fA-F]{64}$. Max input list=500 **до** dedup.

**Response:** 200 ChecksumExistsResponse {existing:String[],missing:String[]}; lowercase, уникально, порядок первого появления внутри каждой группы.

**Errors:** 400 VALIDATION_ERROR либо BAD_REQUEST over max; 401; 404 NOT_FOUND target folder.

**Эффект и ограничения:** Read-only user+folder+lower(file_item.checksum); ни INSERT, ни disk check.

**Повтор / идемпотентность:** Read repeat безопасен; existing не является резервированием или подтверждением доступности байтов.

## 31. GET /api/v1/folders/root

[CONFIRMED] Auth: access.

**Request / validation:** Нет параметров/body.

**Response:** 200 FolderDto ROOT {id,parentId:null,name:'root',folderType:'ROOT',createdAt,updatedAt}.

**Errors:** 401; DB/race errors не унифицированы в 409.

**Эффект и ограничения:** Читает или INSERT ROOT; Camera/Files не создаёт.

**Повтор / идемпотентность:** Повтор возвращает ту же папку при неизменном состоянии, первый GET пишет БД.

## 32. GET /api/v1/folders/{id}/children

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; pagination/recursive flag нет.

**Response:** 200 List<FolderDto> прямых children; ORDER BY lower(name),id. Пустая папка → [].

**Errors:** 401; 404 NOT_FOUND parent missing/foreign.

**Эффект и ограничения:** Только чтение.

**Повтор / идемпотентность:** Read repeat безопасен.

## 33. POST /api/v1/folders

[CONFIRMED] Auth: access.

**Request / validation:** JSON CreateFolderRequest {parentId:Long optional,name:String @NotBlank,@Size(max=255)}. Service trim; no parent → root; parent должен ROOT/USER; Camera/Files names в root зарезервированы.

**Response:** 200 FolderDto нового USER.

**Errors:** 400 VALIDATION_ERROR/BAD_REQUEST system leaf/blank; 401; 404 NOT_FOUND; 409 CONFLICT name/reserved; конкурентный DB failure может выйти 500.

**Эффект и ограничения:** INSERT USER folder; возможно lazy ROOT. Physical directory не создаётся.

**Повтор / идемпотентность:** Не идемпотентен: повтор name+parent → 409.

## 34. PATCH /api/v1/folders/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON RenameFolderRequest {name:String @NotBlank,@Size(max=255)}; trim; только USER.

**Response:** 200 FolderDto.

**Errors:** 400 system folder/validation; 401; 404 NOT_FOUND; 409 CONFLICT duplicate/reserved; поздняя constraint race может стать 500.

**Эффект и ограничения:** UPDATE name и updatedAt; disk unchanged.

**Повтор / идемпотентность:** Повтор того же имени обычно допустим.

## 35. POST /api/v1/folders/{id}/move

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; JSON MoveFolderRequest {targetParentId:Long @NotNull}. USER → ROOT/USER; не self/descendant; ownership обоих IDs.

**Response:** 200 FolderDto.

**Errors:** 400 system folder/leaf/self/descendant/validation; 401; 404 NOT_FOUND; 409 CONFLICT target name/reserved; DB failures.

**Эффект и ограничения:** UPDATE parent_id/updatedAt; descendants через parent chain остаются связаны; файлы физически не перемещаются.

**Повтор / идемпотентность:** Повтор той же target обычно допустим; concurrent graph changes отдельно не сериализованы.

## 36. DELETE /api/v1/folders/{id}

[CONFIRMED] Auth: access.

**Request / validation:** Path id:Long; только empty USER: без child folders и file_items.

**Response:** 204 без body.

**Errors:** 400 BAD_REQUEST system/nonempty; 401; 404 NOT_FOUND; concurrent FK error возможен как 500.

**Эффект и ограничения:** DELETE folder row; нет physical storage операций.

**Повтор / идемпотентность:** Повтор после успеха →404; по состоянию идемпотентен.

## Связанные документы

[Auth](02-auth-contract.md), [ошибки и повторы](03-error-contract.md), [типы и ownership полей](04-media-data-contract.md), [upload/sync](05-sync-upload-contract.md), [возможности](06-server-capabilities.md), [расхождения старых документов](07-api-contract-differences.md), [вопросы клиенту](08-client-relevant-open-questions.md).

[CONFIRMED] Источники трассировки: controller/*.java, SecurityConfig.java, JwtAuthenticationFilter.java, model/dto/*.java, UserService.java, JwtService.java, EmailRequestService.java, FileItemService.java, FolderService.java, ChecksumSyncService.java, GlobalExceptionHandler.java. Они указаны для проверяемости, но контракт выше самодостаточен.

