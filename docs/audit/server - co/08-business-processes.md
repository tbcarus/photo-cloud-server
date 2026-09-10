# 08. Процессы end-to-end

Источники всех [CONFIRMED] последовательностей: [controllers](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/controller), [UserService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/UserService.java), [JwtService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/JwtService.java), [EmailRequestService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/EmailRequestService.java), [FileItemService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FileItemService.java), [FolderService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/FolderService.java), [ChecksumSyncService](C:/projects/photo-cloud-server/src/main/java/ru/tbcarus/photocloudserver/service/sync/ChecksumSyncService.java).

## Register / confirm

[CONFIRMED] Вход email/password JSON → validation → exists lowercase email → BCrypt → save User с USER/disabled → generateEmailRequest(ACTIVATE) → render/send SMTP →201 message. User и email code сохраняются до send, общей транзакции нет. Duplicate409, DTO400, runtime SMTP может прервать response. Catch MessagingException пытается логировать cause.message и продолжить; null cause может породить NPE.

[CONFIRMED] Вход code query → confirmRegistration → email_requests.findByCode → type/used/createdAt+3days → user by ID → транзакционно used=true/enabled=true →200 текст. Invalid/used/expired/wrong type400. Остальные ACTIVATE-коды пользователя остаются как были. Final state — enabled User и consumed code.

## Login / refresh / logout

[CONFIRMED] Login: controller.login(LoginRequest) → UserService.login → repository.findByEmailIgnoreCase → password/enabled/banned checks → save lastLoginAt → generateAccessToken → generateRefreshToken и INSERT refresh_token →200 LoginResponse. Неуспех credentials401; сохранённый lastLoginAt может остаться даже если выдача refresh упала. FS не участвует.

[CONFIRMED] Refresh: controller.refresh(RefreshRequest) → findByToken → user lookup → revoked check → signature/type/exp/subject → generateAccessToken →200 RefreshResponse. Ни rotation, ни last-use write нет. Unknown/invalid401, revoked403; enabled/banned не проверяются.

~~~mermaid
sequenceDiagram
  participant C as Client
  participant U as UserService
  participant D as PostgreSQL
  participant J as JwtService
  C->>U: login(email,password)
  U->>D: find user, load roles
  U->>U: password + enabled + banned
  U->>D: update lastLoginAt
  U->>J: generate access / refresh
  J->>D: insert refresh token with jti
  U-->>C: 200 accessToken, refreshToken
  C->>U: refresh(refreshToken)
  U->>J: lookup / validate stored token
  J->>D: read token row
  U->>D: read user
  J->>J: revoked, JWT type, signature, exp, subject
  U-->>C: 200 accessToken only
~~~

[CONFIRMED] Logout: security access → UserService.logout → getRefreshTokenForLogout → ownership → revoke. Один token получает revokedAt, bulk logout-all/others обновляет только revoked. Final state не содержит server session termination и не инвалидирует access. Retry own token200, unknown404, foreign403. При reset password эти шаги не вызываются.

~~~mermaid
sequenceDiagram
  participant C as Client
  participant S as JWT security
  participant J as JwtService
  participant D as PostgreSQL
  C->>S: logout with access + refresh body
  S->>J: validate access signature / subject
  S->>J: revokeOwnedToken(refresh, principal)
  J->>D: find refresh and verify owner
  J->>D: revoked=true, revokedAt=now
  J-->>C: 200 message
  Note over C,S: Access token остаётся valid до exp
  C->>J: refresh revoked token
  J->>D: read revoked=true
  J-->>C: 403 REFRESH_TOKEN_REVOKED
~~~

## Password reset

[CONFIRMED] Query email → forgotPassword → find user or400 → новый PASSWORD_RESET code → SMTP →200. Повтор всегда создаёт код; limit method не используется. Письмо содержит URL reset/page?code=..., GET которого возвращает501. Отдельный JSON confirm может быть вызван напрямую клиентом: validate password/code → проверить request → обновить hash и used для reset-кодов за3days →200. Final refresh/access state не меняется.

## Existence check

[CONFIRMED] POST checksums/exists принимает folderId + list64hex. DTO validation → max raw list count500 → FolderService ownership → lowercase + LinkedHashSet → query DISTINCT lower(FileItem.checksum) по user+folder → ordered existing/missing. DB/FS writes нет. Отсутствующая/чужая folder404. «Existing» значит logical record; physical file не проверяется. Проверка не резервирует место и не блокирует конкурентный upload/delete.

## Upload и duplicate detection

[CONFIRMED] Multipart file + optional folderId → isEmpty → stream temp+SHA256+size → Tika MIME → FileType → metadata extraction → capturedAt fallback → default/explicit folder → sanitized logical name → find existing same user/folder/checksum.

[CONFIRMED] При duplicate: удалить temp, отдать уже сохранённый FileItemDto с200. Сервер не сравнивает новое имя с прошлым, не обновляет dates/metadata и не проверяет old bytes. Наличие того же checksum в другой folder не используется; готовится новый StoredObject.

[CONFIRMED] При новом файле: name-check кроме CAMERA → new path/UUID → move temp→final → TransactionTemplate StoredObject+FileItem+metadata → mapper →200. Constraint race после final компенсируется удалением собственного файла и requery matching FileItem. IO/runtime cleanup best effort; детали07.

~~~mermaid
sequenceDiagram
  participant C as Client
  participant A as FileController
  participant F as FileItemService
  participant P as FolderService
  participant D as PostgreSQL
  participant S as Filesystem
  C->>A: multipart file, optional folderId
  A->>F: uploadFile
  F->>S: write temp, count bytes, SHA-256
  F->>S: detect MIME and extract metadata
  F->>P: resolve folder / default
  P->>D: lookup or create system folder
  F->>D: find user-folder-checksum
  alt Already exists
    F->>S: delete temp
    F-->>C: 200 existing DTO
  else New content in folder
    F->>D: check name
    F->>S: move temp to final
    F->>D: transaction StoredObject + FileItem + metadata
    alt Commit succeeded
      F-->>C: 200 new DTO
    else DB failure
      F->>S: best-effort delete own final
      F->>D: requery if constraint race
      F-->>C: existing DTO or error
    end
  end
~~~

## Повторная загрузка и потеря сети

[CONFIRMED] Нет upload-id, ACK endpoint, chunk offset или client checksum part. Сервер знает успех как completed write+DB commit; клиент получает200 DTO. Если соединение потеряно после commit, сервер не создаёт запись «ответ не доставлен».

[INFERRED] Retry тех же неизменных bytes в ту же неизменную target folder converges к той же записи через SHA256. Это не гарантирует сохранение исходного ID при intervening delete/move. Pre-check existing после потери ответа не возвращает ID; list либо повтор upload вернёт DTO. Полный retransmit может потребоваться.

## List / metadata / download

[CONFIRMED] GET files → PageRequest0/10 с fixed descending sort → findAllByUserId [AndFolderId] c EntityGraph → DTO map → PageResponse. Optional folder проверяется на ownership; только direct files. Нет дерева recursively, snapshot, type/from/to фильтра.

[CONFIRMED] GET files/{id} → user-scoped lookup → mapper →200, FS не проверяется. Download → тот же lookup → StoredObject path resolver → UrlResource.exists/isReadable → ResponseEntity MIME+attachment → servlet пишет bytes. У missing/foreign/missing physical одинаковый404 FILE_ITEM_NOT_FOUND. Содержимое не декодируется и не перекодируется.

## Rename / move / copy

| Процесс | Вход и sequence | DB/FS и финал | Ошибки |
| --- | --- | --- | --- |
| [CONFIRMED] Rename | id + originalName → own item → trim/sanitize → name-check → save | logical name обновлён; physical name неизменен; DTO200 |400/404/409; DB500 race |
| [CONFIRMED] Move | id + targetFolderId → own item/target → name-check → save folder | FileItem folder изменён; bytes на старом storage path; DTO200 |400/404/409; checksum constraint500 |
| [CONFIRMED] Copy | id + optional target/name → own item/target → name/checksum check → Files.copy → transaction new object/item/metadata | Новые IDs/UUID/bytes, uploadedAt новый, capturedAt и metadata прежние |400/404/409; IO/runtime cleanup, race500 |

Copy в исходную папку конфликтует по checksum даже с другим именем. Move в CAMERA допускает любое detected FileType: обязательной классификации содержимого этой папки нет, тип влияет только на default upload.

## Delete file

[CONFIRMED] id → user-scoped item → resolve physical path → сравнить owners. Non-owner object: delete только item. Owner: transaction delete all item references, flush, delete object → commit → Files.deleteIfExists →204. Ошибка IO последнего шага логируется; итог DB deletion не откатывается. Следующий delete404; повторить filesystem cleanup тем же ID невозможно.

~~~mermaid
sequenceDiagram
  participant C as Client
  participant F as FileItemService
  participant D as PostgreSQL
  participant S as Filesystem
  C->>F: DELETE fileId with access
  F->>D: own FileItem and StoredObject
  alt Item user equals object owner
    F->>D: transaction delete all references + object
    D-->>F: commit
    F->>S: deleteIfExists
    Note over F,S: IOException only logged
  else Logical reference owned by another object owner
    F->>D: delete own FileItem only
  end
  F-->>C: 204 no body
~~~

## Folder operations

[CONFIRMED] ROOT GET создаёт root если нет. USER create выбирает explicit parent либо lazy ROOT, trim name, запрещает CAMERA/FILES parent, reserved root names и same-parent name. Rename/move разрешены только USER; move проходит parent chain для запрета self/descendant. Delete проверяет отсутствие folders/files. Write methods transactional; changes затрагивают только folder table и timestamps, physical storage не отражает дерево.

[INFERRED] Одновременный move двух ветвей/создание root не доказаны как сериализованные операции: prechecks не эквивалентны lock. Названия createRootRaceSafe в коде не дают отдельной гарантии восстановления PostgreSQL transaction после unique violation.
