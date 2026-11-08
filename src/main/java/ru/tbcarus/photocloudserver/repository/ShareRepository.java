package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Share;
import ru.tbcarus.photocloudserver.model.ShareKind;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShareRepository extends JpaRepository<Share, Long> {

    // Действующий USER-доступ к папке или её предку. Цепочка предков не выходит за владельца папки,
    // owner записи должен совпасть с фактическим владельцем, а сам владелец — быть активен и не заблокирован.
    // ROOT и AUTO_UPLOADS не открывают доступ, даже если такая запись появится. UNION обрывает цикл дерева.
    @Query(value = """
            WITH RECURSIVE chain(id, parent_id, user_id, folder_type) AS (
                SELECT id, parent_id, user_id, folder_type FROM folder WHERE id = :folderId
                UNION
                SELECT f.id, f.parent_id, f.user_id, f.folder_type
                FROM folder f JOIN chain c ON f.id = c.parent_id AND f.user_id = c.user_id
            )
            SELECT EXISTS (
                SELECT 1
                FROM share s
                JOIN chain c ON s.folder_id = c.id AND s.owner_id = c.user_id
                JOIN users o ON o.id = s.owner_id
                WHERE s.kind = 'USER'
                  AND s.recipient_id = :recipientId
                  AND (s.expires_at IS NULL OR :now < s.expires_at)
                  AND c.folder_type NOT IN ('ROOT', 'AUTO_UPLOADS')
                  AND o.enabled AND NOT o.banned
            )
            """, nativeQuery = true)
    boolean existsActiveUserShareForFolder(Long folderId, Long recipientId, Instant now);

    // Файл открыт напрямую или через папку, в которой лежит, и её предков; правила те же, что для папки.
    // Файл, его папка и физический объект должны принадлежать одному владельцу.
    @Query(value = """
            WITH RECURSIVE target AS (
                SELECT fi.id, fi.folder_id, fi.user_id
                FROM file_item fi
                JOIN stored_object so ON so.id = fi.stored_object_id AND so.user_id = fi.user_id
                WHERE fi.id = :fileId
            ), chain(id, parent_id, user_id, folder_type) AS (
                SELECT f.id, f.parent_id, f.user_id, f.folder_type
                FROM folder f JOIN target t ON f.id = t.folder_id AND f.user_id = t.user_id
                UNION
                SELECT f.id, f.parent_id, f.user_id, f.folder_type
                FROM folder f JOIN chain c ON f.id = c.parent_id AND f.user_id = c.user_id
            )
            SELECT EXISTS (
                SELECT 1
                FROM share s
                JOIN users o ON o.id = s.owner_id
                WHERE s.kind = 'USER'
                  AND s.recipient_id = :recipientId
                  AND (s.expires_at IS NULL OR :now < s.expires_at)
                  AND o.enabled AND NOT o.banned
                  AND (EXISTS (SELECT 1 FROM target t WHERE s.file_item_id = t.id AND s.owner_id = t.user_id)
                    OR EXISTS (SELECT 1 FROM chain c
                               WHERE s.folder_id = c.id AND s.owner_id = c.user_id
                                 AND c.folder_type NOT IN ('ROOT', 'AUTO_UPLOADS')))
            )
            """, nativeQuery = true)
    boolean existsActiveUserShareForFile(Long fileId, Long recipientId, Instant now);

    // Управление USER-доступами: каждое условие включает владельца или получателя, поэтому чужой shareId неотличим от отсутствующего.
    // Изменения — массовыми запросами: параллельное удаление даёт 0 строк, а не ошибку устаревшей сущности.

    // Запросы владельца принимают вид доступа: USER и LINK управляются отдельными API и друг друга не затрагивают.
    @EntityGraph(attributePaths = {"folder", "fileItem", "recipient"})
    @Query("""
            SELECT s FROM Share s
            WHERE s.owner.id = :ownerId AND s.kind = :kind
              AND (:folderId IS NULL OR s.folder.id = :folderId)
              AND (:fileId IS NULL OR s.fileItem.id = :fileId)
            """)
    Page<Share> findOwnShares(Long ownerId, ShareKind kind, Long folderId, Long fileId, Pageable pageable);

    @EntityGraph(attributePaths = {"folder", "fileItem", "recipient"})
    @Query("""
            SELECT s FROM Share s
            WHERE s.id = :id AND s.owner.id = :ownerId AND s.kind = :kind
            """)
    Optional<Share> findOwnShare(Long id, Long ownerId, ShareKind kind);

    @EntityGraph(attributePaths = {"folder", "fileItem", "recipient"})
    @Query("""
            SELECT s FROM Share s
            WHERE s.owner.id = :ownerId AND s.kind = ru.tbcarus.photocloudserver.model.ShareKind.USER
              AND s.recipient.id IN :recipientIds
              AND (:folderId IS NULL OR s.folder.id = :folderId)
              AND (:fileId IS NULL OR s.fileItem.id = :fileId)
            """)
    List<Share> findUserSharesForRecipients(Long ownerId, Long folderId, Long fileId, Collection<Long> recipientIds);

    // Без clearAutomatically: grant вызывает его в цикле, и очистка отсоединяла бы папку, файл и получателей,
    // уже загруженных в транзакции. Новые Share ссылались бы на отсоединённые объекты, а итоговое чтение
    // создало бы для тех же строк вторые экземпляры в одном контексте. Обновляемые Share до этого в контекст
    // не загружаются, поэтому устаревших управляемых копий не остаётся.
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Share s SET s.expiresAt = :expiresAt
            WHERE s.owner.id = :ownerId AND s.kind = ru.tbcarus.photocloudserver.model.ShareKind.USER
              AND s.recipient.id = :recipientId
              AND (:folderId IS NULL OR s.folder.id = :folderId)
              AND (:fileId IS NULL OR s.fileItem.id = :fileId)
            """)
    int updateUserShareExpiry(Long ownerId, Long folderId, Long fileId, Long recipientId, Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Share s SET s.expiresAt = :expiresAt
            WHERE s.id = :id AND s.owner.id = :ownerId AND s.kind = :kind
            """)
    int updateOwnShareExpiry(Long id, Long ownerId, ShareKind kind, Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM Share s
            WHERE s.id = :id AND s.owner.id = :ownerId AND s.kind = :kind
            """)
    int deleteOwnShare(Long id, Long ownerId, ShareKind kind);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM Share s
            WHERE s.owner.id = :ownerId AND s.kind = :kind
              AND (:folderId IS NULL OR s.folder.id = :folderId)
              AND (:fileId IS NULL OR s.fileItem.id = :fileId)
            """)
    int deleteOwnSharesOfResource(Long ownerId, ShareKind kind, Long folderId, Long fileId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM Share s
            WHERE s.id = :id AND s.recipient.id = :recipientId AND s.kind = ru.tbcarus.photocloudserver.model.ShareKind.USER
            """)
    int deleteUserShareByIdAndRecipient(Long id, Long recipientId);

    // Прямые действующие доступы получателя по тем же условиям, что и existsActiveUserShare*: иначе в списке
    // оказался бы объект, который read-endpoints не откроют.
    @EntityGraph(attributePaths = {"owner", "folder", "fileItem", "fileItem.storedObject", "fileItem.metadata"})
    @Query(value = """
            SELECT s FROM Share s
            JOIN s.owner o
            LEFT JOIN s.folder f
            LEFT JOIN s.fileItem fi
            LEFT JOIN fi.storedObject so
            WHERE s.kind = ru.tbcarus.photocloudserver.model.ShareKind.USER
              AND s.recipient.id = :recipientId
              AND (s.expiresAt IS NULL OR :now < s.expiresAt)
              AND o.enabled = true AND o.banned = false
              AND ((f.id IS NOT NULL AND f.user.id = o.id
                    AND f.folderType NOT IN (ru.tbcarus.photocloudserver.model.FolderType.ROOT,
                                             ru.tbcarus.photocloudserver.model.FolderType.AUTO_UPLOADS))
                OR (fi.id IS NOT NULL AND fi.user.id = o.id AND so.user.id = o.id))
            """,
            countQuery = """
            SELECT count(s) FROM Share s
            JOIN s.owner o
            LEFT JOIN s.folder f
            LEFT JOIN s.fileItem fi
            LEFT JOIN fi.storedObject so
            WHERE s.kind = ru.tbcarus.photocloudserver.model.ShareKind.USER
              AND s.recipient.id = :recipientId
              AND (s.expiresAt IS NULL OR :now < s.expiresAt)
              AND o.enabled = true AND o.banned = false
              AND ((f.id IS NOT NULL AND f.user.id = o.id
                    AND f.folderType NOT IN (ru.tbcarus.photocloudserver.model.FolderType.ROOT,
                                             ru.tbcarus.photocloudserver.model.FolderType.AUTO_UPLOADS))
                OR (fi.id IS NOT NULL AND fi.user.id = o.id AND so.user.id = o.id))
            """)
    Page<Share> findActiveSharedWith(Long recipientId, Instant now, Pageable pageable);

    // Публичная ссылка ищется только по SHA-256 токена; остальные проверки выполняет LinkAccess.
    @EntityGraph(attributePaths = {"owner", "folder", "fileItem", "fileItem.storedObject", "fileItem.folder"})
    Optional<Share> findByTokenHashAndKind(String tokenHash, ShareKind kind);
}
