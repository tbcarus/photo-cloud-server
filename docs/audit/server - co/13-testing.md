# 13. Тесты и доказательность покрытия

## Метод проверки

[CONFIRMED] Найдены 88 @Test methods в7 конкретных test classes, плюс общий AbstractIntegrationTest. Это инвентаризация исходников, **не число успешно пройденных тестов**. Аудит не запускал Gradle/test/server: команды создали бы build/cache/temp artifacts и изменили тестовую БД, тогда как разрешена запись только новых отчётов. Существующие build outputs не приняты за доказательство текущего результата.

[CONFIRMED] build.gradle: JUnit Platform, Spring Boot starter-test, security-test, Testcontainers, PostgreSQLContainer. JaCoCo/code coverage configuration, нагрузочный harness и CI workflow в tracked дереве не найдены. Процент покрытия не вычислялся.

## Тестовое окружение

[CONFIRMED] AbstractIntegrationTest — @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles(test) + @Testcontainers + @DirtiesContext(AFTER_CLASS). PostgreSQL16-alpine, Liquibase real migrations + Hibernate validate; настоящий локальный temp storage. Это integration в JVM MockMvc, не реальный сетевой TCP/TLS/servlet upload тест.

[CONFIRMED] Dynamic properties заменяют DB credentials/URL и storage, limit=1024 bytes. BeforeEach выполняет TRUNCATE всех8 прикладных таблиц RESTART IDENTITY CASCADE и очищает temp storage; @AfterAll удаляет тестовую директорию. Поэтому эти тесты нельзя безоговорочно направлять на существующую БД. Аудит их не запускал.

[CONFIRMED] FileItemServiceTest — mock repositories, extractor и transaction manager, настоящие FileUtils/Tika/path/sanitizer и @TempDir; mock transaction не подтверждает PostgreSQL concurrency/rollback. ChecksumSyncServiceTest — mocks repositories/FolderService, проверка normalized query inputs.

## Coverage by scenario

| Subsystem | Tests found | Coverage by scenario | Missing critical scenarios |
| --- | --- | --- | --- |
| [CONFIRMED] Bootstrap/DB | PhotoCloudServerApplicationTests:1 | Context startup по Testcontainers + полный clean-schema changelog | Upgrade из populated media_file, миграции11/12/14 на проблемных данных, production schema |
| [CONFIRMED] Auth/recovery | AuthErrorHandlingIntegrationTest:30 | Validation, invalid/used/expired activation, invalid reset, successful reset, login flags/errors, duplicate registration, invalid/revoked refresh, own/foreign/unknown logout, logout-others, invalid/missing access | Successful refresh, expired ACCESS/REFRESH, refresh Bearer misuse, public invalid Bearer, blocking after issuance, reset revocation expectations, logout-all, concurrent revoke/refresh |
| [CONFIRMED] Profile | ProfileIntegrationTest:1 | Поля DTO, createdAt vs createAt, displayName, lastLoginAt, no first/last | 501 endpoints, access after ban, nullable legacy fields |
| [CONFIRMED] File API/storage | FileFlowIntegrationTest:39 | Upload/duplicate/scope/names, new physical copy, move/rename, list/sort, own/foreign, MIME, size/temp errors, checksum exists cases, download/delete, paths, metadata fallback | Explicit /files/upload route, true multipart limit, range/large download memory, copy partial IO, delete IO, concurrent name/checksum/move conflicts, success EXIF/GPS/video, recovery after kill |
| [CONFIRMED] Folder API | FolderApiIntegrationTest:13 | Lazy root sequentially, system constraints, reserved/duplicate names, nested parents, cycles, ownership, empty delete, children filtering | Successful rename/move, create without parent, simultaneous ROOT creation and cycles, deep trees, race empty-check/delete |
| [CONFIRMED] Upload compensation | FileItemServiceTest:2 | Final cleanup DB failure; simulated duplicate race returns existing | Real PostgreSQL parallel upload; commit and mapper failure split; cleanup itself fails |
| [CONFIRMED] Checksum normalization | ChecksumSyncServiceTest:2 | lowercase/dedup/order; raw-size rejected before dependency calls | Huge inputs/performance; corrupt DB checksum/physical divergence |
| [PARTIAL] Manual API | docs/http-tests/api-smoke-tests.http |26 request entries; examples auth/profile/files | All6 folder routes, explicit upload, file rename/move/copy; automatic assertions absent |
| [PARTIAL] curl examples | docs/http-tests/api-curl-examples.md | Только переменные и заголовок RootController | curl-команды отсутствуют |

## Ограничения конкретных assertions

- [INCONSISTENCY] FileFlowIntegrationTest.ownerDeleteRemovesAllFileItemsStoredObjectAndPhysicalFile делает два same-content upload в одну default folder. Теперь second.id=first.id; проверка двух findById не доказывает удаление **нескольких** references. Non-owner test создаёт межпользовательскую logical reference напрямую и проверяет её удаление.
- [INCONSISTENCY] FolderApiIntegrationTest.cannotRenameRootCameraOrFiles фактически проверяет ROOT и CAMERA; FILES не включён в тело этого теста.
- [PARTIAL] metadataExtractionFailureDoesNotFailUpload подаёт text bytes с image MIME header, который Tika игнорирует; поэтому тест не гарантирует вход в image extractor exception branch. У настоящего extractor catch присутствует по коду.
- [PARTIAL] uploadNewFileStoresMetadataFoldersAndPhysicalFile проверяет metadata=null для минимального JPEG; successful rich EXIF/GPS/fNumber serialization не подтверждается этим тестом.
- [PARTIAL] tempFileIsRemovedWhenStreamingFails catches IOException без assertThrown; отсутствие DB/files проверено, факт исключения отдельным assertion не утверждается.
- [CONFIRMED] fileItemDtoReturnsPhysicalFieldsFromStoredObject меняет checksum/size/MIME/type только StoredObject SQL, доказывая DTO source в assertions, но оставляет FileItem.checksum отличным. Это полезное свидетельство разделённых checksum источников, не доказательство согласованности.
- [PARTIAL] raceConditionRemovesCurrentFinalFileAndReturnsExistingFileItem использует mock sequential returns и mock DataIntegrityViolation; проверки двух real DB transactions отсутствуют.

## Полный перечень @Test методов

Ниже автоматическая инвентаризация declaration @Test в прочитанных исходниках; сообщения о прохождении не подразумеваются.

### PhotoCloudServerApplicationTests.java — 1

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/PhotoCloudServerApplicationTests.java).

- [CONFIRMED] contextLoads

### AuthErrorHandlingIntegrationTest.java — 30

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/AuthErrorHandlingIntegrationTest.java).

- [CONFIRMED] invalidRegistrationCodeReturnsBadRequestErrorResponse
- [CONFIRMED] usedRegistrationCodeReturnsBadRequestErrorResponse
- [CONFIRMED] expiredRegistrationCodeReturnsBadRequestErrorResponse
- [CONFIRMED] invalidPasswordResetCodeReturnsBadRequestErrorResponse
- [CONFIRMED] passwordResetRequestValidationRejectsBlankEmail
- [CONFIRMED] passwordResetConfirmValidationRejectsBlankPasswordAndCode
- [CONFIRMED] passwordResetConfirmRejectsLegacyQueryParamContract
- [CONFIRMED] successfulPasswordReset
- [CONFIRMED] passwordResetConfirmValidationRejectsTooShortPassword
- [CONFIRMED] passwordResetConfirmValidationRejectsTooLongPassword
- [CONFIRMED] loginValidationRejectsNullAndBlankFields
- [CONFIRMED] loginWithUnknownEmailReturnsUnauthorizedErrorResponse
- [CONFIRMED] loginWithWrongPasswordReturnsUnauthorizedErrorResponse
- [CONFIRMED] bannedUserCannotLogin
- [CONFIRMED] disabledUserCannotLogin
- [CONFIRMED] successfulLoginUpdatesLastLoginAt
- [CONFIRMED] registerValidationRejectsNullAndBlankFields
- [CONFIRMED] duplicateRegisterReturnsConflictErrorResponse
- [CONFIRMED] refreshValidationRejectsBlankRefreshToken
- [CONFIRMED] refreshWithUnknownTokenReturnsUnauthorizedErrorResponse
- [CONFIRMED] refreshWithRevokedTokenReturnsForbiddenErrorResponse
- [CONFIRMED] logoutValidationRejectsBlankRefreshToken
- [CONFIRMED] logoutWithForeignRefreshTokenReturnsForbidden
- [CONFIRMED] logoutWithUnknownRefreshTokenReturnsNotFound
- [CONFIRMED] logoutWithOwnRefreshTokenReturnsOk
- [CONFIRMED] logoutOthersWithForeignRefreshTokenReturnsForbiddenWithoutRevokingOwnTokens
- [CONFIRMED] logoutOthersWithUnknownRefreshTokenReturnsNotFound
- [CONFIRMED] logoutOthersWithOwnRefreshTokenKeepsCurrentAndRevokesOtherOwnTokens
- [CONFIRMED] protectedEndpointWithoutTokenReturnsUnauthorizedErrorResponse
- [CONFIRMED] protectedEndpointWithInvalidJwtReturnsUnauthorizedErrorResponse

### FileFlowIntegrationTest.java — 39

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/FileFlowIntegrationTest.java).

- [CONFIRMED] uploadNewFileStoresMetadataFoldersAndPhysicalFile
- [CONFIRMED] duplicateUploadIntoSameFolderReturnsExistingFileItemWithoutCreatingDuplicates
- [CONFIRMED] listFilesByFolderReturnsOnlyDirectFilesFromThatFolder
- [CONFIRMED] uploadCanTargetFolderAndDefaultUploadStillUsesSystemFolders
- [CONFIRMED] uploadSameContentIntoDifferentFolderCreatesNewStoredObjectAndFileItem
- [CONFIRMED] duplicateNameConflictsOutsideCameraAndCameraIsIdempotentPerChecksum
- [CONFIRMED] copyCreatesNewStoredObjectPhysicalFileInAnotherFolderAndDoesNotUseDedup
- [CONFIRMED] copyInSameFolderWithoutNewNameConflictsOutsideCamera
- [CONFIRMED] copyIntoSameFolderWithNewNameIsRejectedAsChecksumDuplicate
- [CONFIRMED] moveChangesOnlyFolderAndKeepsStoredObjectAndPhysicalFile
- [CONFIRMED] renameChangesOnlyOriginalNameAndKeepsPhysicalFilename
- [CONFIRMED] renameConflictIsRejectedOutsideCamera
- [CONFIRMED] foreignFolderCannotBeUsedForListUploadCopyOrMove
- [CONFIRMED] uploadDoesNotCallMultipartFileGetBytes
- [CONFIRMED] uploadOverMaxFileSizeReturnsErrorAndRemovesTempFile
- [CONFIRMED] tempFileIsRemovedWhenStreamingFails
- [CONFIRMED] mimeTypeIsDetectedFromContentInsteadOfMultipartHeader
- [CONFIRMED] listFilesReturnsOnlyCurrentUserFilesAndStablePaginationFields
- [CONFIRMED] listFilesSortsByCapturedAtUploadedAtAndIdDescending
- [CONFIRMED] metadataReturnsCurrentUserFile
- [CONFIRMED] downloadReturnsOriginalBytesAndContentType
- [CONFIRMED] fileItemDtoReturnsPhysicalFieldsFromStoredObject
- [CONFIRMED] checksumsReturnsOnlyCurrentUserChecksumsWithIds
- [CONFIRMED] checksumExistsRequiresAuthentication
- [CONFIRMED] checksumExistsValidatesRequestBody
- [CONFIRMED] checksumExistsRejectsForeignOrMissingFolder
- [CONFIRMED] checksumExistsRejectsBatchOverLimit
- [CONFIRMED] checksumExistsReturnsExistingAndMissingChecksumsOnlyForCurrentUser
- [CONFIRMED] checksumExistsHandlesAllMissingAllExistingDuplicatesAndUppercase
- [CONFIRMED] checksumExistsIsScopedToFolderAndUser
- [CONFIRMED] deleteRemovesDatabaseRowsAndPhysicalFileWithoutBody
- [CONFIRMED] ownerDeleteRemovesAllFileItemsStoredObjectAndPhysicalFile
- [CONFIRMED] nonOwnerDeleteRemovesOnlyFileItemAndKeepsStoredObjectAndPhysicalFile
- [CONFIRMED] foreignMissingAndMissingPhysicalFilesReturnNotFound
- [CONFIRMED] fileEndpointWithoutOrInvalidTokenReturnsUnauthorized
- [CONFIRMED] defaultFoldersPhysicalPathAndFilenameSafetyAreApplied
- [CONFIRMED] physicalFilenameComponentIsLimitedTo255Characters
- [CONFIRMED] capturedAtFallsBackToUploadedAtAndMetadataIsNullableWhenExtractionHasNoData
- [CONFIRMED] metadataExtractionFailureDoesNotFailUpload

### FolderApiIntegrationTest.java — 13

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/FolderApiIntegrationTest.java).

- [CONFIRMED] rootIsCreatedOnlyOnce
- [CONFIRMED] cannotDeleteRootCameraOrFiles
- [CONFIRMED] cannotMoveRootCameraOrFiles
- [CONFIRMED] cannotRenameRootCameraOrFiles
- [CONFIRMED] cannotCreateFolderInsideCameraOrFiles
- [CONFIRMED] duplicateNameInSameParentIsRejectedCaseInsensitive
- [CONFIRMED] cameraAndFilesNamesAreReservedInRoot
- [CONFIRMED] sameNameIsAllowedInDifferentParents
- [CONFIRMED] cannotMoveFolderIntoItselfOrDescendant
- [CONFIRMED] cannotWorkWithForeignFolder
- [CONFIRMED] deleteOnlyEmptyUserFolder
- [CONFIRMED] childrenReturnsOnlyCurrentUserFolders
- [CONFIRMED] uploadContinuesToUseDefaultFolders

### ProfileIntegrationTest.java — 1

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/integration/ProfileIntegrationTest.java).

- [CONFIRMED] profileUsesCurrentUserFields

### FileItemServiceTest.java — 2

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/service/FileItemServiceTest.java).

- [CONFIRMED] finalFileIsRemovedWhenDatabaseSaveFailsAfterMove
- [CONFIRMED] raceConditionRemovesCurrentFinalFileAndReturnsExistingFileItem

### ChecksumSyncServiceTest.java — 2

Источник: [test source](C:/projects/photo-cloud-server/src/test/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncServiceTest.java).

- [CONFIRMED] checkExistingNormalizesDeduplicatesAndKeepsResponseOrder
- [CONFIRMED] checkExistingRejectsBatchOverLimitBeforeFolderAndRepositoryCalls

## Логирование тестов

[CONFIRMED] Test helper маскирует accessToken/refreshToken в JSON и Authorization header, но не password или code; URLs с query также печатаются. build.gradle showStandardStreams=true делает это частью test output. Тестовые credentials не переписаны в отчёт.
