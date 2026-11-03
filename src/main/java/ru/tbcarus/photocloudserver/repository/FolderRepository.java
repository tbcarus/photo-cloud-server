package ru.tbcarus.photocloudserver.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    Optional<Folder> findByUserIdAndParentIsNullAndFolderType(Long userId, FolderType folderType);

    Optional<Folder> findByUserIdAndParentIdAndFolderType(Long userId, Long parentId, FolderType folderType);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent IS NULL AND f.folderType = ru.tbcarus.photocloudserver.model.FolderType.ROOT")
    Optional<Folder> findRootByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent IS NULL AND f.folderType = ru.tbcarus.photocloudserver.model.FolderType.ROOT")
    Optional<Folder> findRootByUserIdForUpdate(Long userId);

    // ON CONFLICT не обрывает внешнюю транзакцию: проигравший параллельный запрос просто перечитывает ROOT.
    @Modifying
    @Query(value = "INSERT INTO folder (user_id, name, folder_type) VALUES (:userId, :name, 'ROOT') ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void insertRootIfAbsent(Long userId, String name);

    Optional<Folder> findByIdAndUserId(Long id, Long userId);

    Optional<Folder> findByUserIdAndBackupSource(Long userId, UUID backupSource);

    // Тот же порядок, что у файлов при sort=name: не зависит от локали БД.
    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId "
            + "ORDER BY sql('lower(? collate \"ru-x-icu\")', f.name), f.id")
    List<Folder> findChildrenByUserIdAndParentId(Long userId, Long parentId);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId AND lower(f.name) = lower(:name)")
    Optional<Folder> findByUserIdAndParentIdAndName(Long userId, Long parentId, String name);

    @Query("SELECT count(f) > 0 FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId AND lower(f.name) = lower(:name)")
    boolean existsByUserIdAndParentIdAndName(Long userId, Long parentId, String name);

    @Query("SELECT f.user.id FROM Folder f WHERE f.id = :id")
    Optional<Long> findOwnerId(Long id);

    // Имена от верхней папки к заданной; ROOT соответствует корню библиотеки и в путь не входит.
    @Query(value = """
            WITH RECURSIVE chain(id, parent_id, name, depth) AS (
                SELECT id, parent_id, name, 0 FROM folder WHERE id = :folderId
                UNION ALL
                SELECT f.id, f.parent_id, f.name, c.depth + 1 FROM folder f JOIN chain c ON f.id = c.parent_id
            )
            SELECT name FROM chain WHERE parent_id IS NOT NULL ORDER BY depth DESC
            """, nativeQuery = true)
    List<String> findPathNames(Long folderId);

    // Маркер активного источника ищется у самой папки и её предков: на вложенных объектах он не дублируется.
    @Query(value = """
            WITH RECURSIVE chain(id, parent_id, backup_source) AS (
                SELECT id, parent_id, backup_source FROM folder WHERE id = :folderId
                UNION ALL
                SELECT f.id, f.parent_id, f.backup_source FROM folder f JOIN chain c ON f.id = c.parent_id
            )
            SELECT CAST(backup_source AS varchar) FROM chain WHERE backup_source IS NOT NULL LIMIT 1
            """, nativeQuery = true)
    Optional<String> findActiveBackupSource(Long folderId);

    // Вложенные папки удаляет ON DELETE CASCADE.
    @Modifying
    @Query(value = "DELETE FROM folder WHERE id = :folderId", nativeQuery = true)
    void deleteTree(Long folderId);
}
