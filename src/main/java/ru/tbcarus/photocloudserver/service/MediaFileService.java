package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.exception.MediaFileNotFoundException;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.MediaType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.model.dto.mapper.MediaFileMapper;
import ru.tbcarus.photocloudserver.repository.MediaFileRepository;
import ru.tbcarus.photocloudserver.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaFileService {
    // Stores media files and exposes current media-related business operations.

    private final MediaFileRepository mediaFileRepository;
    private final MediaFileMapper mediaFileMapper;

    @Value("${storage.baseStoragePath}")
    private String baseStoragePath;

    public MediaFileDto uploadFile(MultipartFile file, User user) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("MIME-тип не определён");
        }

        byte[] fileBytes = file.getBytes();
        String checksum = FileUtils.calculateSHA256(fileBytes);
        Optional<MediaFile> duplicate = mediaFileRepository.findByUserIdAndChecksum(user.getId(), checksum);
        if (duplicate.isPresent()) {
            return mediaFileMapper.toDto(duplicate.get());
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "file";
        }
        String extension = FilenameUtils.getExtension(originalFilename);
        String storageFilename = originalFilename + "." + UUID.randomUUID() + "." + extension;

        Path userDir = Paths.get(baseStoragePath, user.getId().toString());
        Files.createDirectories(userDir);

        Path destination = userDir.resolve(storageFilename);
        String storagePath = destination.toAbsolutePath().normalize().toString();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            log.info("File saved: {}", destination.toAbsolutePath());

            MediaFile mediaFile = MediaFile.builder()
                    .originalFilename(originalFilename)
                    .storageFilename(storageFilename)
                    .storagePath(storagePath)
                    .thumbnailPath("")
                    .createdAt(LocalDateTime.now())
                    .mimeType(mimeType)
                    .size(file.getSize())
                    .type(MediaType.fromMimeType(mimeType))
                    .user(user)
                    .checksum(checksum)
                    .build();

            return mediaFileMapper.toDto(mediaFileRepository.save(mediaFile));
        } catch (RuntimeException | IOException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
    }

    public Page<MediaFileDto> getUserFiles(Pageable pageable, User user) {
        return mediaFileRepository.findAllByUserId(user.getId().longValue(), pageable).map(mediaFileMapper::toDto);
    }

    public MediaFile getFileForCurrentUser(Long fileId, User user) {
        return mediaFileRepository.findByIdAndUserId(fileId, user.getId())
                .orElseThrow(() -> new MediaFileNotFoundException(fileId));
    }

    public MediaFileDto getFileDtoForCurrentUser(Long fileId, User user) {
        return mediaFileMapper.toDto(getFileForCurrentUser(fileId, user));
    }

    public void deleteFileForCurrentUser(Long fileId, User user) throws IOException {
        MediaFile file = getFileForCurrentUser(fileId, user);
        Path path = Paths.get(file.getStoragePath());
        Files.deleteIfExists(path);
        mediaFileRepository.delete(file);
    }

    public List<MediaFileChecksumDto> getChecksumsForUser(Long userId) {
        return mediaFileRepository.findAllChecksumsAndOriginalFilenamesByUserId(userId);
    }
}
