package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.FileMetadata;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
}
