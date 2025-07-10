package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.dto.MediaFileChecksumDto;

import java.util.List;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {
    List<MediaFile> findAllByUserId(Long userId);

    Page<MediaFile> findAllByUserId(Long userId, Pageable pageable);

    @Query("SELECT new ru.tbcarus.photocloudserver.model.dto.MediaFileChecksumDto(m.checksum, m.originalFilename) " +
            "FROM MediaFile m WHERE m.user.id = :userId")
    List<MediaFileChecksumDto> findAllChecksumsAndOriginalFilenamesByUserId(Long userId);
}
