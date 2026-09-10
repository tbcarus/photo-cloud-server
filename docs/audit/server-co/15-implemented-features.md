# Master feature matrix

[CONFIRMED] Status обозначает наличие реализации в этом checkout, а не успешный deployment или runtime coverage. IMPLEMENTED = есть рабочая ветка; PARTIAL = часть сценария; STUB = endpoint/method без реализации; UNUSED = код без production caller; NOT FOUND = не найден механизм из заявленной области проекта/аудита. Будущие идеи сверх документов проекта сюда не добавлялись.

| Feature | Status | Main components | Notes |
| --- | --- | --- | --- |
| Register email/password | IMPLEMENTED | RegisterController, UserService | Disabled USER + BCrypt + code |
| Email activation | IMPLEMENTED | EmailRequestService.confirmRegistration | 3 days, used flag, GET mutation |
| Resend activation | STUB | RegisterController.resendVerifyEmail | 501 |
| Login | IMPLEMENTED | UserService.login, JwtService | enabled/banned checks, new refresh each time |
| Access JWT validation | IMPLEMENTED | JwtAuthenticationFilter | Signature/type/sub/exp; banned not rechecked |
| Refresh JWT | IMPLEMENTED | JwtService.refreshAccessToken | DB lookup, no rotation |
| Logout current/all/others | IMPLEMENTED | AuthController, JwtService | Refresh revoke only |
| Multiple logins | IMPLEMENTED | JwtService.generateRefreshToken | Multiple rows, no device identity |
| Device/session management | PARTIAL | RefreshToken model | Revocation rows; device list/names/state absent |
| Roles | PARTIAL | User.roles, Role, SecurityConfig | USER/ADMIN exist; no role-specific business API |
| Password reset request | IMPLEMENTED | UserService.forgotPassword | New code + SMTP, no active rate limit |
| Password reset JSON confirm | IMPLEMENTED | EmailRequestService.resetPassword | Hash change, reset codes used; tokens remain |
| Password reset browser flow | PARTIAL | EmailService, PasswordController | Link points to501 |
| Password reset resend/page GET/page POST | STUB | PasswordController | 3 endpoints501 |
| Profile read | IMPLEMENTED | UserController.getProfile | UserDto |
| Profile edit/settings GET/settings edit | STUB | UserController | 3 endpoints501 |
| Delete account | NOT FOUND | TODO.txt | No endpoint/service cleanup flow |
| SMTP HTML | IMPLEMENTED | MailConfig, EmailService, templates | Synchronous; delivery handling incomplete |
| Email attempt cap helper | UNUSED | EmailRequestService.checkAndGenerateCode | Active endpoints bypass |
| Email delete helper | STUB | EmailRequestService.delete | Empty method |
| Old confirm by email+code | UNUSED | EmailRequestService.confirmEmail | Current API uses safer code owner path |
| Logical folders | IMPLEMENTED | FolderController, FolderService | 6 routes, ROOT/Camera/Files/USER |
| Folder rename/move | IMPLEMENTED | FolderService | Only USER, no descendant move |
| Empty USER folder delete | IMPLEMENTED | FolderService.deleteFolder | Hard delete |
| Recursive folder delete | NOT FOUND | FolderService TODO | Nonempty400 |
| Upload compatibility alias | IMPLEMENTED | POST /files | Same pipeline as explicit alias |
| Explicit upload | IMPLEMENTED | POST /files/upload | No separate test invocation |
| Streaming SHA-256 / size | IMPLEMENTED | FileUtils, FileItemService | 8192 buffer,100MiB default |
| Temp/final compensation | PARTIAL | FileItemService | Exceptions covered best effort, process crashes not recovered |
| Content MIME detection | IMPLEMENTED | FileContentDetector/Tika | Does not trust multipart MIME |
| Image EXIF/GPS | PARTIAL | DrewFileMetadataExtractor | Image-only; dimensions JPEG/PNG; exceptions swallowed |
| Video upload/download | IMPLEMENTED | FileType, FileItemService | Bytes accepted by general pipeline |
| Video duration/metadata | PARTIAL | FileMetadata.durationSec | Field exists, current extractor never fills duration |
| Logical name/path separation | IMPLEMENTED | FileItem/StoredObject/storage helpers | Rename/move without disk move |
| Per-folder checksum uniqueness | IMPLEMENTED | FileItemRepository, SQL14 | user+folder+checksum |
| Name conflict handling | PARTIAL | FileItemService.ensureFileNameAvailable | CAMERA exempt, no DB unique outside CAMERA |
| File list/card/download | IMPLEMENTED | FileController | PageResponse; physical paths hidden |
| File rename/move | IMPLEMENTED | FileItemService | Move checksum conflict may be500 |
| Independent file copy | IMPLEMENTED | FileItemService.copyFile... | Physical copy, new IDs; same-folder conflict |
| File hard delete | IMPLEMENTED | FileItemService.deleteFile... | DB first, disk deletion best effort |
| Shared-reference delete branch | PARTIAL | FileItemService; model FK | No sharing grant API; tests can seed shared reference |
| Sharing/link permissions | NOT FOUND | TODOs, README | No public link/ACL/token model |
| Trash/restore | PARTIAL | FileItem.deletedAt | Column exists, no state transitions/endpoints |
| Checksum full list | IMPLEMENTED | GET /files/checksums | Unpaginated per FileItem |
| Folder checksum batch pre-check | IMPLEMENTED | ChecksumSyncService | Max500 input, no disk check |
| Full sync/device sessions | NOT FOUND | docs/api-checksum-sync-contract.md TODO | No cursor/change feed/deletions |
| Link existing by checksum | NOT FOUND | ChecksumSyncService TODO | /copy requires file ID and duplicates bytes |
| Lookup SO by checksum | UNUSED | StoredObjectRepository.findFirstByUserIdAndChecksumOrderByIdAsc | No production caller |
| Direct metadata repository | UNUSED | FileMetadataRepository | Metadata persisted via FileItem cascade |
| Reverse registration mapper | UNUSED | UserRegisterMapper.toUserRegisterDto | No caller |
| Albums/search/tags | NOT FOUND | README development list | No entities/endpoints |
| Thumbnails/preview | NOT FOUND | Historical migration06 / TODO.txt | thumbnail_path deleted with media_file |
| Overwrite/auto-rename/versioning | NOT FOUND | FileItemService TODO / api-file-contract.md | Conflict, no replacement |
| Quota state | NOT FOUND | README-description.md client LIMIT idea | No quota response or persisted usage |
| Retry/resume/chunk upload | NOT FOUND | controller/service inventory | No upload ID or completion endpoint |
| Background cleanup | NOT FOUND | service/config inventory | No scheduler |
| OpenAPI UI | IMPLEMENTED | springdoc, @Operation | Dependency/config present; live document not fetched |
| Connectivity checks | IMPLEMENTED | RootController | /test, /test/auth |
| Unified errors | PARTIAL | GlobalExceptionHandler, filter handlers | Custom selected errors only |
| HTTP/email logging | IMPLEMENTED | HttpLoggingFilter, EmailService | Sensitive content masking absent |
| Metrics/tracing/readiness/audit trail | NOT FOUND | build/config/model scan | Simple test endpoint only |
| Unit/integration tests | IMPLEMENTED | src/test | 88 declared @Test, not run in this audit |
| Automated manual-smoke assertions | NOT FOUND | api-smoke-tests.http | Requests/comments only |

