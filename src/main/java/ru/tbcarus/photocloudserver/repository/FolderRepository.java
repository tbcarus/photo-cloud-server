package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.FolderType;

import java.util.Optional;

@Repository
public interface FolderRepository extends JpaRepository<Folder, Long> {
    Optional<Folder> findByUserIdAndParentIsNullAndFolderType(Long userId, FolderType folderType);

    Optional<Folder> findByUserIdAndParentIdAndFolderType(Long userId, Long parentId, FolderType folderType);
}
