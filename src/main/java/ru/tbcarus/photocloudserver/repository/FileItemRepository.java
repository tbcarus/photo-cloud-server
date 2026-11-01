package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FileItemRepository extends JpaRepository<FileItem, Long> {

    // Явный JPQL: выражение сортировки по имени с COLLATE не поддерживается производными запросами.
    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    @Query("SELECT f FROM FileItem f WHERE f.user.id = :userId")
    Page<FileItem> findAllByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    @Query("SELECT f FROM FileItem f WHERE f.user.id = :userId AND f.folder.id = :folderId")
    Page<FileItem> findAllByUserIdAndFolderId(Long userId, Long folderId, Pageable pageable);

    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    Optional<FileItem> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    @Query("SELECT f FROM FileItem f WHERE f.id = :id")
    Optional<FileItem> findWithRelationsById(Long id);

    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    List<FileItem> findAllByStoredObjectId(Long storedObjectId);

    @Query("SELECT new ru.tbcarus.photocloudserver.model.dto.FileChecksumDto(f.id, f.originalName, f.storedObject.checksum) " +
            "FROM FileItem f WHERE f.user.id = :userId")
    List<FileChecksumDto> findAllChecksumsAndOriginalFilenamesByUserId(Long userId);

    // Дубль в Camera определяется как user + folder + checksum; одинаковый checksum в другой папке дублем не считается.
    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    Optional<FileItem> findFirstByUserIdAndFolderIdAndChecksumOrderByIdAsc(Long userId, Long folderId, String checksum);

    @Query("""
            SELECT DISTINCT lower(f.checksum)
            FROM FileItem f
            WHERE f.user.id = :userId
              AND f.folder.id = :folderId
              AND lower(f.checksum) IN :checksums
            """)
    Set<String> findExistingChecksumsInFolder(Long userId, Long folderId, Collection<String> checksums);

    @Query("SELECT f.user.id FROM FileItem f WHERE f.id = :id")
    Optional<Long> findOwnerId(Long id);

    // Файлы поддерева и их StoredObject; метаданные удаляет ON DELETE CASCADE.
    @Modifying
    @Query(value = """
            WITH RECURSIVE tree(id) AS (
                SELECT CAST(:folderId AS bigint)
                UNION ALL
                SELECT f.id FROM folder f JOIN tree t ON f.parent_id = t.id
            ), removed AS (
                DELETE FROM file_item WHERE folder_id IN (SELECT id FROM tree) RETURNING stored_object_id
            )
            DELETE FROM stored_object WHERE id IN (SELECT stored_object_id FROM removed)
            """, nativeQuery = true)
    void deleteAllInFolderTree(Long folderId);

    // Только для обычной библиотеки: в Camera имена повторяются. Имена сравнивает одна функция PostgreSQL lower().
    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    @Query("""
            SELECT f
            FROM FileItem f
            WHERE f.user.id = :userId
              AND f.folder.id = :folderId
              AND lower(f.originalName) = lower(:name)
            """)
    Optional<FileItem> findInFolderByName(Long userId, Long folderId, String name);
}
