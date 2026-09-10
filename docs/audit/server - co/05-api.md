# 05. Полная инвентаризация API

[CONFIRMED] В 7 @RestController найдено 36 явных method/path mappings, включая 7 заглушек. ApiPaths.API_V1 = /api/v1. Контекст приложения /, порт по конфигурации8080. Это контракт исходного кода; HTTP-запросы во время аудита не выполнялись.

## Общие правила

- Public не требует Authorization; access означает точный заголовок `Authorization: Bearer <ACCESS JWT>`.
- Для protected: 401 UNAUTHORIZED при отсутствии/невалидности access; общий 403 FORBIDDEN предусмотрен security handler. Невалидный Bearer может дать 401 даже на public URL.
- JSON DTO-операции используют @Valid/@Validated. Ошибки validation400 описаны в 10; `ErrorResponse={id:UUID,code:string,message:string,fieldErrors:object|null}`.
- @PathVariable Long и numeric query проходят MVC conversion, но отдельного handler для type mismatch нет; точное framework body не обещается.
- Для IOException, LazyInitializationException, mail runtime, generic500 нет catch-all ErrorResponse. Это не гарантированный JSON INTERNAL_ERROR.
- Все mappings имеют @Operation summary и @Tag. Swagger endpoints предоставляет dependency, они не входят в 36 собственных mappings.
- Tests shorthand: A=AuthErrorHandlingIntegrationTest; F=FileFlowIntegrationTest; O=FolderApiIntegrationTest; P=ProfileIntegrationTest. «Есть тест» значит найден сценарий в исходниках, а не успешно выполнен.
- В response FileItemDto поля: id,folderId,originalFilename,mimeType,size,checksum,fileType,capturedAt,uploadedAt,deletedAt,metadata. FolderDto: id,parentId,name,folderType,createdAt,updatedAt. Полный wire dictionary — client-handoff/04.

## Сводная таблица

| Method | URI | Controller.method | Auth | Success / implementation | Tests | Contract doc |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/test` | RootController.testPermitAll | public | 200 IMPLEMENTED | JUnit не найден; ручной .http | api-user-contract.md |
| GET | `/api/v1/test/auth` | RootController.testAuth | access | 200 IMPLEMENTED | JUnit не найден; ручной .http | api-user-contract.md |
| POST | `/api/v1/auth/register` | RegisterController.register | public | 201 IMPLEMENTED | A: registerValidationRejectsNullAndBlankFields, duplicateRegisterReturnsConflictErrorResponse; успешной регистрации/SMTP нет | api-user-contract.md |
| GET | `/api/v1/auth/register/confirm` | RegisterController.verifyEmail | public | 200 IMPLEMENTED | A: invalid/used/expiredRegistrationCodeReturnsBadRequestErrorResponse; success не найден | api-user-contract.md |
| POST | `/api/v1/auth/register/resend` | RegisterController.resendVerifyEmail | public | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| POST | `/api/v1/auth/login` | AuthController.login | public | 200 IMPLEMENTED | A: login validation/unknown/wrong/disabled/banned/successfulLoginUpdatesLastLoginAt; helpers F/O/P | api-user-contract.md |
| POST | `/api/v1/auth/refresh-token` | AuthController.refresh | public | 200 IMPLEMENTED | A: refreshValidationRejectsBlankRefreshToken, refreshWithUnknownTokenReturnsUnauthorizedErrorResponse, refreshWithRevokedTokenReturnsForbiddenErrorResponse; success/expiry не найдены | api-user-contract.md |
| POST | `/api/v1/auth/logout` | AuthController.logout | access | 200 IMPLEMENTED | A: logout validation/foreign/unknown/own | api-user-contract.md |
| POST | `/api/v1/auth/logout-all` | AuthController.logoutAll | access | 200 IMPLEMENTED | JUnit не найден; ручной .http | api-user-contract.md |
| POST | `/api/v1/auth/logout-others` | AuthController.logoutOthers | access | 200 IMPLEMENTED | A: logoutOthers foreign/unknown/own | api-user-contract.md |
| POST | `/api/v1/auth/password/reset/request` | PasswordController.forgotPassword | public | 200 IMPLEMENTED | A: passwordResetRequestValidationRejectsBlankEmail; success не найден | api-user-contract.md |
| POST | `/api/v1/auth/password/reset/confirm` | PasswordController.resetPassword | public | 200 IMPLEMENTED | A: successfulPasswordReset, invalid code, validation, rejectsLegacyQueryParamContract | api-user-contract.md |
| POST | `/api/v1/auth/password/reset/resend` | PasswordController.resendResetPassword | public | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| GET | `/api/v1/auth/password/reset/page` | PasswordController.getResetPasswordPage | public | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| POST | `/api/v1/auth/password/reset/page` | PasswordController.submitResetPasswordPage | public | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| GET | `/api/v1/profile` | UserController.getProfile | access | 200 IMPLEMENTED | P: profileUsesCurrentUserFields; A: protected invalid/missing JWT | api-user-contract.md |
| PATCH | `/api/v1/profile` | UserController.updateProfile | access | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| GET | `/api/v1/profile/settings` | UserController.getSettings | access | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| PATCH | `/api/v1/profile/settings` | UserController.updateSettings | access | 501 STUB | JUnit не найден; ручной .http | api-user-contract.md |
| GET | `/api/v1/folders/root` | FolderController.getRoot | access | 200 IMPLEMENTED | O: rootIsCreatedOnlyOnce; helpers F | api-folder-contract.md |
| GET | `/api/v1/folders/{id}/children` | FolderController.getChildren | access | 200 IMPLEMENTED | O: childrenReturnsOnlyCurrentUserFolders, cannotWorkWithForeignFolder | api-folder-contract.md |
| POST | `/api/v1/folders` | FolderController.createFolder | access | 200 IMPLEMENTED | O: создание в helpers, duplicate/reserved/sameNameDifferentParents/systemLeaf | api-folder-contract.md |
| PATCH | `/api/v1/folders/{id}` | FolderController.renameFolder | access | 200 IMPLEMENTED | O: cannotRenameRootCameraOrFiles (фактически ROOT/CAMERA); success не найден | api-folder-contract.md |
| POST | `/api/v1/folders/{id}/move` | FolderController.moveFolder | access | 200 IMPLEMENTED | O: cannotMoveRootCameraOrFiles, cannotMoveFolderIntoItselfOrDescendant, foreign; success не найден | api-folder-contract.md |
| DELETE | `/api/v1/folders/{id}` | FolderController.deleteFolder | access | 204 IMPLEMENTED | O: deleteOnlyEmptyUserFolder, cannotDeleteRootCameraOrFiles | api-folder-contract.md |
| POST | `/api/v1/files` | FileController.uploadFile | access | 200 IMPLEMENTED | F: upload*, duplicateUpload*, MIME, folders, limits; FileItemServiceTest cleanup/race | api-file-contract.md |
| POST | `/api/v1/files/upload` | FileController.uploadFileExplicit | access | 200 IMPLEMENTED | Прямой JUnit/ручной .http не найден; общий service покрыт через alias | api-file-contract.md |
| GET | `/api/v1/files` | FileController.getUserFiles | access | 200 IMPLEMENTED | F: listFilesByFolder*, listFilesReturnsOnlyCurrentUserFilesAndStablePaginationFields, sorting | api-file-contract.md |
| GET | `/api/v1/files/{id}` | FileController.getFile | access | 200 IMPLEMENTED | F: metadataReturnsCurrentUserFile, fileItemDtoReturnsPhysicalFieldsFromStoredObject, foreignMissing* | api-file-contract.md |
| GET | `/api/v1/files/{id}/download` | FileController.downloadFile | access | 200 IMPLEMENTED | F: downloadReturnsOriginalBytesAndContentType, foreignMissingAndMissingPhysicalFilesReturnNotFound | api-file-contract.md |
| PATCH | `/api/v1/files/{id}` | FileController.renameFile | access | 200 IMPLEMENTED | F: renameChangesOnlyOriginalNameAndKeepsPhysicalFilename, renameConflictIsRejectedOutsideCamera | api-file-contract.md |
| POST | `/api/v1/files/{id}/move` | FileController.moveFile | access | 200 IMPLEMENTED | F: moveChangesOnlyFolderAndKeepsStoredObjectAndPhysicalFile, foreignFolderCannotBeUsedForListUploadCopyOrMove; checksum conflict не тестируется | api-file-contract.md |
| POST | `/api/v1/files/{id}/copy` | FileController.copyFile | access | 200 IMPLEMENTED | F: copyCreatesNewStoredObjectPhysicalFileInAnotherFolderAndDoesNotUseDedup, copyInSameFolder*, copyIntoSameFolderWithNewName* | api-file-contract.md |
| DELETE | `/api/v1/files/{id}` | FileController.deleteFile | access | 204 IMPLEMENTED | F: deleteRemovesDatabaseRowsAndPhysicalFileWithoutBody, ownerDelete*, nonOwnerDelete* | api-file-contract.md |
| GET | `/api/v1/files/checksums` | FileController.getChecksums | access | 200 IMPLEMENTED | F: checksumsReturnsOnlyCurrentUserChecksumsWithIds | api-file-contract.md |
| POST | `/api/v1/files/checksums/exists` | FileController.checkExistingChecksums | access | 200 IMPLEMENTED | F: checksumExists*; ChecksumSyncServiceTest normalization/order/max | api-checksum-sync-contract.md |

## Подробные операции

### GET /api/v1/test

[CONFIRMED] Источник: [RootController.testPermitAll](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RootController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет параметров/тела. |
| Response / status | 200 JSON {message: "All good! Permit all connection"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | БД/FS: нет. |
| Идемпотентность | Да, чтение. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/test/auth

[CONFIRMED] Источник: [RootController.testAuth](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RootController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров/тела. |
| Response / status | 200 JSON {message: "All good! Authenticated connection. Hello <email>"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | Principal из БД; записей/FS нет. |
| Идемпотентность | Да. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/register

[CONFIRMED] Источник: [RegisterController.register](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RegisterController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON RegisterRequest: email string @NotBlank @Email; password string @NotBlank @Size(4..20). displayName не объявлен. Email длиной >128 не отвергается DTO-лимитом. |
| Response / status | 201 JSON {message: "Email was sent"}. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 409 CONFLICT email; DB constraint 500 при гонке/длине; SMTP/runtime не имеют единого ErrorResponse. |
| DB / filesystem / side effects | users + user_roles; email_requests ACTIVATE; синхронный SMTP. Общей транзакции нет. |
| Идемпотентность | Нет: повтор существующего email →409; успех не подтверждает доставку письма. |
| Тесты | A: registerValidationRejectsNullAndBlankFields, duplicateRegisterReturnsConflictErrorResponse; успешной регистрации/SMTP нет |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/auth/register/confirm

[CONFIRMED] Источник: [RegisterController.verifyEmail](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RegisterController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Query code string обязателен; @NotBlank нет. |
| Response / status | 200 текст User <email> was verified. |
| Ошибки | 400 BAD_REGISTRATION_REQUEST при missing DB code/used/expired/wrong type; missing query →400 BAD_REQUEST. |
| DB / filesystem / side effects | Транзакция: users.enabled=true, email_requests.used=true; FS нет. |
| Идемпотентность | Нет одинакового ответа: использованный код →400, user остаётся enabled. |
| Тесты | A: invalid/used/expiredRegistrationCodeReturnsBadRequestErrorResponse; success не найден |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/register/resend

[CONFIRMED] Источник: [RegisterController.resendVerifyEmail](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/RegisterController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров. |
| Response / status | 501 JSON {message: "Registration confirmation resend is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет DB/FS/SMTP. |
| Идемпотентность | Постоянная заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/login

[CONFIRMED] Источник: [AuthController.login](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON LoginRequest: email @NotBlank @Email; password @NotBlank, length 4..20. |
| Response / status | 200 LoginResponse {accessToken:string, refreshToken:string}. Нет expiresIn/tokenType. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_CREDENTIALS для unknown email, bad password, disabled/banned. |
| DB / filesystem / side effects | users.lastLoginAt, lastUpdate; INSERT refresh_token; FS нет. |
| Идемпотентность | Нет: каждый успех создаёт новый refresh (UUID jti). |
| Тесты | A: login validation/unknown/wrong/disabled/banned/successfulLoginUpdatesLastLoginAt; helpers F/O/P |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/refresh-token

[CONFIRMED] Источник: [AuthController.refresh](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON RefreshRequest {refreshToken:string @NotBlank}. Передавать refresh в body, не как access Bearer. |
| Response / status | 200 RefreshResponse {accessToken:string}. Refresh остаётся прежним. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 401 INVALID_REFRESH_TOKEN unknown/malformed/expired/wrong type/subject; 403 REFRESH_TOKEN_REVOKED; invalid Authorization →401 UNAUTHORIZED до controller. |
| DB / filesystem / side effects | Чтение refresh_token и users; новых DB rows/FS нет. |
| Идемпотентность | Не потребляет refresh; повтор допустим, access заново генерируется. |
| Тесты | A: refreshValidationRejectsBlankRefreshToken, refreshWithUnknownTokenReturnsUnauthorizedErrorResponse, refreshWithRevokedTokenReturnsForbiddenErrorResponse; success/expiry не найдены |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/logout

[CONFIRMED] Источник: [AuthController.logout](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON LogoutRequest {refreshToken:string @NotBlank}; token должен принадлежать principal. |
| Response / status | 200 JSON {message: "Logged out successfully"}. |
| Ошибки | 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR. |
| DB / filesystem / side effects | revoked=true, revokedAt=now одной строки; access не отзывается. |
| Идемпотентность | Повтор собственного существующего token →200, revokedAt обновится. |
| Тесты | A: logout validation/foreign/unknown/own |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/logout-all

[CONFIRMED] Источник: [AuthController.logoutAll](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет тела/параметров. |
| Response / status | 200 JSON {message: "All logged out successfully"}. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | Все revoked=false по userName → revoked=true; revokedAt не меняется. |
| Идемпотентность | Повтор →200; новые login между запросами меняют множество. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/logout-others

[CONFIRMED] Источник: [AuthController.logoutOthers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/AuthController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON LogoutRequest {refreshToken:string @NotBlank}; существующий token текущего user. Его expiry/revoked не проверяются. |
| Response / status | 200 JSON {message: "All other logged out successfully"}. |
| Ошибки | 400 validation/body; 404 REFRESH_TOKEN_NOT_FOUND; 403 REFRESH_TOKEN_OWNERSHIP_ERROR. |
| DB / filesystem / side effects | Все неотозванные tokens того же user кроме переданного → revoked=true; revokedAt не меняется. |
| Идемпотентность | Повтор не создаёт новых записей; не привязан к device. |
| Тесты | A: logoutOthers foreign/unknown/own |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/password/reset/request

[CONFIRMED] Источник: [PasswordController.forgotPassword](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Обязательный query email string @NotBlank; @Email здесь отсутствует. JSON email не является объявленным контрактом. |
| Response / status | 200 JSON {message: "Email was sent"}. |
| Ошибки | 400 VALIDATION_ERROR blank; 400 BAD_REQUEST missing query/unknown user; SMTP/runtime нестандартизованы. |
| DB / filesystem / side effects | Создаёт PASSWORD_RESET email_request и синхронно посылает письмо; FS нет. |
| Идемпотентность | Нет: повтор создаёт новый код и письмо, rate-limit не подключён. |
| Тесты | A: passwordResetRequestValidationRejectsBlankEmail; success не найден |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/password/reset/confirm

[CONFIRMED] Источник: [PasswordController.resetPassword](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | JSON PasswordResetConfirmRequest: password @NotBlank size4..20; code @NotBlank. Legacy query-only не принимается. |
| Response / status | 200 JSON {message: "Password was reset"}. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST; 400 BAD_REGISTRATION_REQUEST invalid/used/expired/wrong-type code. |
| DB / filesystem / side effects | Транзакция password hash + used текущего кода и reset-кодов user за последние 3 дня. Refresh/access не отзываются. |
| Идемпотентность | Повтор consumed code →400. |
| Тесты | A: successfulPasswordReset, invalid code, validation, rejectsLegacyQueryParamContract |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/password/reset/resend

[CONFIRMED] Источник: [PasswordController.resendResetPassword](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров. |
| Response / status | 501 JSON {message: "Password reset resend is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/auth/password/reset/page

[CONFIRMED] Источник: [PasswordController.getResetPasswordPage](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Query code string обязателен, иначе 400; code не проверяется по БД. |
| Response / status | 501 JSON {message: "Password reset page is not implemented yet"}. HTML не возвращается. |
| Ошибки | 400 BAD_REQUEST missing query; 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет; сюда ведёт reset email. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/auth/password/reset/page

[CONFIRMED] Источник: [PasswordController.submitResetPasswordPage](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/PasswordController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | public |
| Request / validation / ограничения | Нет объявленного тела/параметров; form не обрабатывается. |
| Response / status | 501 JSON {message: "Password reset page submit is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/profile

[CONFIRMED] Источник: [UserController.getProfile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/UserController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 200 UserDto: id,email,displayName,enabled,banned,roles,createdAt,lastUpdate,lastLoginAt. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | DTO principal из БД; записей/FS нет. |
| Идемпотентность | Да. |
| Тесты | P: profileUsesCurrentUserFields; A: protected invalid/missing JWT |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### PATCH /api/v1/profile

[CONFIRMED] Источник: [UserController.updateProfile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/UserController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет request DTO; присланные поля не обрабатываются. |
| Response / status | 501 JSON {message: "Profile update is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/profile/settings

[CONFIRMED] Источник: [UserController.getSettings](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/UserController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 501 JSON {message: "Profile settings are not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет; settings entity отсутствует. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### PATCH /api/v1/profile/settings

[CONFIRMED] Источник: [UserController.updateSettings](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/UserController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет DTO; присланные настройки не применяются. |
| Response / status | 501 JSON {message: "Profile settings update is not implemented yet"}. |
| Ошибки | 501 не ErrorResponse. |
| DB / filesystem / side effects | Нет. |
| Идемпотентность | Заглушка. |
| Тесты | JUnit не найден; ручной .http |
| Документация | docs/api-user-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/folders/root

[CONFIRMED] Источник: [FolderController.getRoot](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет параметров. |
| Response / status | 200 FolderDto; parentId=null, name=root, folderType=ROOT. |
| Ошибки | DB race может привести к DB/runtime ошибке; нет обещания универсального 409. |
| DB / filesystem / side effects | Ленивый INSERT folder ROOT если отсутствует; FS нет. |
| Идемпотентность | Повтор возвращает тот же ROOT; GET имеет write side effect. |
| Тесты | O: rootIsCreatedOnlyOnce; helpers F |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/folders/{id}/children

[CONFIRMED] Источник: [FolderController.getChildren](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id: Long; обязательный; без pagination/recursive/query filters. |
| Response / status | 200 JSON array FolderDto, прямые потомки, sort lower(name),id. |
| Ошибки | 404 NOT_FOUND missing/foreign parent. |
| DB / filesystem / side effects | Чтение folder; FS нет. |
| Идемпотентность | Да. |
| Тесты | O: childrenReturnsOnlyCurrentUserFolders, cannotWorkWithForeignFolder |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/folders

[CONFIRMED] Источник: [FolderController.createFolder](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON CreateFolderRequest: parentId optional Long (null→ROOT); name @NotBlank @Size(max255); service trim. Parent ROOT/USER. |
| Response / status | 200 FolderDto новой USER-папки. |
| Ошибки | 400 VALIDATION_ERROR/BAD_REQUEST system leaf/blank/length; 404 NOT_FOUND parent; 409 CONFLICT occupied/reserved name; DB race может дать500. |
| DB / filesystem / side effects | INSERT folder, возможно ROOT; FS нет. |
| Идемпотентность | Нет: повтор имени →409. |
| Тесты | O: создание в helpers, duplicate/reserved/sameNameDifferentParents/systemLeaf |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### PATCH /api/v1/folders/{id}

[CONFIRMED] Источник: [FolderController.renameFolder](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. JSON RenameFolderRequest {name:string @NotBlank @Size(max255)}; trim. Только USER. |
| Response / status | 200 FolderDto. |
| Ошибки | 400 BAD_REQUEST system folder / VALIDATION_ERROR; 404 NOT_FOUND; 409 CONFLICT occupied/reserved. |
| DB / filesystem / side effects | UPDATE folder.name, updatedAt; FS нет. |
| Идемпотентность | Повтор имени допустим, updatedAt может измениться. |
| Тесты | O: cannotRenameRootCameraOrFiles (фактически ROOT/CAMERA); success не найден |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/folders/{id}/move

[CONFIRMED] Источник: [FolderController.moveFolder](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON MoveFolderRequest {targetParentId:Long @NotNull}; target ROOT/USER. |
| Response / status | 200 FolderDto. |
| Ошибки | 400 BAD_REQUEST system/self/descendant/system target; 404 NOT_FOUND; 409 CONFLICT names/reserved; validation400. |
| DB / filesystem / side effects | UPDATE parent_id/updatedAt; FS нет. |
| Идемпотентность | Повтор того же parent допустим; конкурентное изменение дерева не сериализовано. |
| Тесты | O: cannotMoveRootCameraOrFiles, cannotMoveFolderIntoItselfOrDescendant, foreign; success не найден |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### DELETE /api/v1/folders/{id}

[CONFIRMED] Источник: [FolderController.deleteFolder](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FolderController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; только USER без children и files. |
| Response / status | 204 без тела. |
| Ошибки | 400 BAD_REQUEST system/nonempty; 404 NOT_FOUND; DB race500 возможен. |
| DB / filesystem / side effects | DELETE folder; FS не затрагивается. |
| Идемпотентность | Эффект удаления повторим; следующий запрос404. |
| Тесты | O: deleteOnlyEmptyUserFolder, cannotDeleteRootCameraOrFiles |
| Документация | docs/api-folder-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/files

[CONFIRMED] Источник: [FileController.uploadFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка. |
| Response / status | 200 FileItemDto и при создании, и при duplicate; нет Location/upload-status. |
| Ошибки | 400 BAD_REQUEST empty file/invalid params; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler. |
| DB / filesystem / side effects | Temp write + SHA-256/size/MIME/metadata → folder resolve → duplicate/name check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate возвращает старый DTO без новых объектов; folders могут быть созданы до final transaction. |
| Идемпотентность | В пределах user+folder+bytes SHA-256; rename нового request игнорируется при duplicate. Не восстанавливает отсутствующий physical file. |
| Тесты | F: upload*, duplicateUpload*, MIME, folders, limits; FileItemServiceTest cleanup/race |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | [DOCUMENTED] Совместимый старый alias; [CONFIRMED] активно используется JUnit |

### POST /api/v1/files/upload

[CONFIRMED] Источник: [FileController.uploadFileExplicit](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | multipart/form-data: обязательная часть file MultipartFile; optional folderId Long multipart/request param. Не JSON. Не принимает client checksum/capturedAt/status. File не пустой, stream max104857600 bytes; servlet max-file/request110MB. При folderId=null IMAGE/VIDEO→CAMERA, остальные→FILES; иначе любая своя папка. |
| Response / status | 200 FileItemDto; тот же pipeline, что POST /files. |
| Ошибки | 400 BAD_REQUEST empty file/invalid params; 401; 404 NOT_FOUND folder; 409 CONFLICT name outside CAMERA; 413 FILE_TOO_LARGE; DB500 кроме разрешённой checksum race; IOException без специального JSON handler. |
| DB / filesystem / side effects | Temp write + SHA-256/size/MIME/metadata → folder resolve → duplicate/name check → final move → transaction INSERT StoredObject/FileItem/optional metadata. Duplicate возвращает старый DTO без новых объектов; folders могут быть созданы до final transaction. |
| Идемпотентность | Те же ограничения, что POST /files. |
| Тесты | Прямой JUnit/ручной .http не найден; общий service покрыт через alias |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/files

[CONFIRMED] Источник: [FileController.getUserFiles](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Query page int default0 >=0, size int default10 >0 (PageRequest); верхний предел size не установлен; optional folderId Long, только прямые файлы. type/from/to/sort не объявлены. |
| Response / status | 200 PageResponse<FileItemDto> {items,page,size,totalElements,totalPages,hasNext,hasPrevious}. Sort capturedAt DESC,uploadedAt DESC,id DESC. |
| Ошибки | 400 BAD_REQUEST negative page/size0; type-conversion400 форма не задана custom handler;404 NOT_FOUND folder. |
| DB / filesystem / side effects | Read DB; нет FS availability check. |
| Идемпотентность | Чтение; между страницами нет snapshot/cursor guarantee. |
| Тесты | F: listFilesByFolder*, listFilesReturnsOnlyCurrentUserFilesAndStablePaginationFields, sorting |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/files/{id}

[CONFIRMED] Источник: [FileController.getFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. |
| Response / status | 200 FileItemDto; physical path/filename/StoredObject ID не раскрываются. |
| Ошибки | 404 FILE_ITEM_NOT_FOUND missing/foreign file. |
| DB / filesystem / side effects | DB only; physical bytes не проверяются. |
| Идемпотентность | Да. |
| Тесты | F: metadataReturnsCurrentUserFile, fileItemDtoReturnsPhysicalFieldsFromStoredObject, foreignMissing* |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/files/{id}/download

[CONFIRMED] Источник: [FileController.downloadFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. Пользовательское Range/ETag поведение не реализовано явно и не закреплено тестами. |
| Response / status | 200 Resource/raw bytes; Content-Type = stored detectedMimeType; Content-Disposition: attachment; filename="<originalName>". |
| Ошибки | 404 FILE_ITEM_NOT_FOUND missing/foreign/unreadable or absent physical; IOException без общего custom500; invalid DB MIME/path может дать400. |
| DB / filesystem / side effects | Read DB + filesystem; response обёрнут HttpLoggingFilter cache. |
| Идемпотентность | Да для неизменного объекта; bytes проверяются только exists/isReadable, checksum не пересчитывается. |
| Тесты | F: downloadReturnsOriginalBytesAndContentType, foreignMissingAndMissingPhysicalFilesReturnNotFound |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### PATCH /api/v1/files/{id}

[CONFIRMED] Источник: [FileController.renameFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON RenameFileRequest {originalName:string @NotBlank @Size(max255)}. Trim + sanitize. Request originalName отличается от response originalFilename. |
| Response / status | 200 FileItemDto. |
| Ошибки | 400 validation/BAD_REQUEST;404 FILE_ITEM_NOT_FOUND;409 CONFLICT name outside CAMERA; race DB errors500. |
| DB / filesystem / side effects | UPDATE FileItem.originalName; physical filename неизменен. |
| Идемпотентность | Повтор того же нормализованного имени допустим. |
| Тесты | F: renameChangesOnlyOriginalNameAndKeepsPhysicalFilename, renameConflictIsRejectedOutsideCamera |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/files/{id}/move

[CONFIRMED] Источник: [FileController.moveFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON MoveFileRequest {targetFolderId:Long @NotNull}. Любая своя папка, включая системную. |
| Response / status | 200 FileItemDto. |
| Ошибки | 400 validation;404 FILE_ITEM_NOT_FOUND/NOT_FOUND folder;409 CONFLICT name outside CAMERA;500 DATABASE_CONSTRAINT_VIOLATION при checksum conflict если name-check пропущен/прошёл. |
| DB / filesystem / side effects | UPDATE FileItem.folder; физический файл не перемещается; checksum precheck в move отсутствует. |
| Идемпотентность | Повтор target допустим если constraints соблюдены; 500 checksum повтор не исправит. |
| Тесты | F: moveChangesOnlyFolderAndKeepsStoredObjectAndPhysicalFile, foreignFolderCannotBeUsedForListUploadCopyOrMove; checksum conflict не тестируется |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### POST /api/v1/files/{id}/copy

[CONFIRMED] Источник: [FileController.copyFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long; JSON CopyFileRequest обязателен, {} допустим. targetFolderId optional(null→source folder); originalName optional @Size(max255), null/blank→source name; иначе trim+sanitize. |
| Response / status | 200 новый FileItemDto. |
| Ошибки | 400 body/validation;404 file/folder;409 CONFLICT name/checksum;500 constraint race; missing source bytes→IOException без специального404 handler. |
| DB / filesystem / side effects | Files.copy → transaction new StoredObject/FileItem/metadata; uploadedAt новый, capturedAt прежний. Новые UUID/path; checksum/type/size/extension наследуются, не пересчитываются. |
| Идемпотентность | Не возвращает существующий объект при повторе: target checksum→409. Copy в исходную папку всегда конфликтует. |
| Тесты | F: copyCreatesNewStoredObjectPhysicalFileInAnotherFolderAndDoesNotUseDedup, copyInSameFolder*, copyIntoSameFolderWithNewName* |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### DELETE /api/v1/files/{id}

[CONFIRMED] Источник: [FileController.deleteFile](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Path id Long. |
| Response / status | 204 без тела. |
| Ошибки | 404 FILE_ITEM_NOT_FOUND; DB errors500; IOException удаления bytes только логируется и не отменяет204. |
| DB / filesystem / side effects | Owner: transaction delete all FileItem references + StoredObject, metadata cascade; после commit Files.deleteIfExists. Non-owner StoredObject: только свой FileItem. |
| Идемпотентность | Повтор →404; не повторяет неудавшееся физическое удаление, если DB row уже нет. |
| Тесты | F: deleteRemovesDatabaseRowsAndPhysicalFileWithoutBody, ownerDelete*, nonOwnerDelete* |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | Нет признаков |

### GET /api/v1/files/checksums

[CONFIRMED] Источник: [FileController.getChecksums](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | Нет query/pagination/filter параметров. |
| Response / status | 200 массив FileChecksumDto {id,originalFilename,checksum}; все FileItem user; folderId отсутствует, порядок не задан. |
| Ошибки | Общие security/framework ошибки. |
| DB / filesystem / side effects | DB projection через StoredObject.checksum, без filesystem. |
| Идемпотентность | Да; одинаковый checksum может повторяться для разных папок. |
| Тесты | F: checksumsReturnsOnlyCurrentUserChecksumsWithIds |
| Документация | docs/api-file-contract.md; @Operation summary |
| Legacy | [DOCUMENTED] Старый список; [CONFIRMED] реализован и тестируется, UNUSED не доказан |

### POST /api/v1/files/checksums/exists

[CONFIRMED] Источник: [FileController.checkExistingChecksums](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller/FileController.java).

| Аспект | Фактическое поведение |
| --- | --- |
| Auth | access |
| Request / validation / ограничения | JSON ChecksumExistsRequest: folderId Long @NotNull; checksums @NotEmpty List; каждый @NotBlank и 64 hex regex. Max raw list size500 до dedup; без trim. Lowercase Locale.ROOT и dedup первого появления. |
| Response / status | 200 ChecksumExistsResponse {existing:string[],missing:string[]}; каждый hash один раз в своей partition, порядок первого появления; нет file IDs. |
| Ошибки | 400 VALIDATION_ERROR null/blank/invalid;400 BAD_REQUEST batch>max;404 NOT_FOUND folder;401. |
| DB / filesystem / side effects | Read file_item.checksum только указанной папки/user; без physical check, lock/reservation/DB writes. |
| Идемпотентность | Да, read-only; результат может устареть до upload. |
| Тесты | F: checksumExists*; ChecksumSyncServiceTest normalization/order/max |
| Документация | docs/api-checksum-sync-contract.md; @Operation summary |
| Legacy | Нет признаков |

## API contract inconsistencies

[CONFIRMED] Файл с точным именем api-contract.md не найден. Сверены четыре api-*-contract.md, application-overview.md, README/README-description/TODO и ручные HTTP-примеры. Полная таблица расхождений находится в [client-handoff/07](C:/projects/photo-cloud-server/docs/audit/server/client-handoff/07-api-contract-differences.md).

[INCONSISTENCY] Наиболее существенные: docs/api-file-contract.md не включает checksum-conflict500 для move; общий формат ошибок не покрывает IOException/runtime; инструкция ручных smoke заявляет все controllers, но не содержит Folder API, explicit upload, rename/move/copy файла; комментарий delete в .http описывает обратный порядок FS/DB; комментарий reset предлагает проверить отзыв токенов, которого код не выполняет. Основные current file/folder/checksum DTO и happy paths в api-*-contract.md согласованы с кодом.

## Framework routes и неопределённые части

[CONFIRMED] (source: config/build) Security разрешает /swagger-ui/**, /swagger-resources/*, /v3/api-docs/**; точное множество springdoc resource mappings не перечислено приложением. Отдельных Actuator/health controllers нет. Служебный /error не включён в permitAll; итог исключений за пределами advice может зависеть от error dispatch/security. HEAD/OPTIONS, Content-Length, Range/206/416, ETag, Cache-Control и CORS не реализованы как отдельный прикладной контракт. Для них нельзя выдумывать server guarantee по отсутствию controller method.

[CONFIRMED] Фактическое использование API Android-клиентом не устанавливалось: код клиента не входит в этот аудит. Legacy alias не означает отсутствие клиентов.
