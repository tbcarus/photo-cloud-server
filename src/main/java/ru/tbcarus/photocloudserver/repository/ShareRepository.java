package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Share;

import java.time.Instant;

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
}
