package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileItemRepository extends JpaRepository<FileItem, Long> {

    @EntityGraph(attributePaths = {"folder", "folder.parent", "storedObject", "metadata"})
    Page<FileItem> findAllByUserId(Long userId, Pageable pageable);

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

    boolean existsByFolderId(Long folderId);
}
