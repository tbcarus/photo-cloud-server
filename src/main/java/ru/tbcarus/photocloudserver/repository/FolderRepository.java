package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.util.List;
import java.util.Optional;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    Optional<Folder> findByUserIdAndParentIsNullAndFolderType(Long userId, FolderType folderType);

    Optional<Folder> findByUserIdAndParentIdAndFolderType(Long userId, Long parentId, FolderType folderType);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent IS NULL AND f.folderType = ru.tbcarus.photocloudserver.model.FolderType.ROOT")
    Optional<Folder> findRootByUserId(Long userId);

    Optional<Folder> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId ORDER BY lower(f.name), f.id")
    List<Folder> findChildrenByUserIdAndParentId(Long userId, Long parentId);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId AND lower(f.name) = lower(:name)")
    Optional<Folder> findByUserIdAndParentIdAndName(Long userId, Long parentId, String name);

    @Query("SELECT count(f) > 0 FROM Folder f WHERE f.user.id = :userId AND f.parent.id = :parentId AND lower(f.name) = lower(:name)")
    boolean existsByUserIdAndParentIdAndName(Long userId, Long parentId, String name);

    boolean existsByParentId(Long parentId);
}
