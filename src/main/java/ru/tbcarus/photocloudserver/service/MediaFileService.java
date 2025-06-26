package ru.tbcarus.photocloudserver.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.exception.EntityNotFoundException;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.MediaType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.model.dto.mapper.MediaFileMapper;
import ru.tbcarus.photocloudserver.repository.MediaFileRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaFileService {

    private final MediaFileRepository mediaFileRepository;
    private final MediaFileMapper mediaFileMapper;

    @Value("${storage.baseStoragePath}")
    private String baseStoragePath;

    public MediaFileDto uploadFile(MultipartFile file, User user) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл пустой");
        }

        String mimeType = file.getContentType();
        if (mimeType == null) {
            throw new IllegalArgumentException("MIME-тип не определён");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = FilenameUtils.getExtension(originalFilename);
        String storageFilename = UUID.randomUUID() + "." + extension;

        Path userDir = Paths.get(baseStoragePath, user.getId().toString());
        Files.createDirectories(userDir);

        Path destination = userDir.resolve(storageFilename);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            log.info("File saved: {}", destination.toAbsolutePath());
        }

        MediaFile mediaFile = MediaFile.builder()
                .originalFilename(originalFilename)
                .storageFilename(storageFilename)
                .mimeType(mimeType)
                .size(file.getSize())
                .type(MediaType.fromMimeType(mimeType))
                .user(user)
                .build();

        return mediaFileMapper.toDto(mediaFileRepository.save(mediaFile));
    }

    public Page<MediaFileDto> getUserFiles(Pageable pageable, User user) {
        return mediaFileRepository.findAllByUserId(user.getId().longValue(), pageable).map(mediaFileMapper::toDto);
    }

    public MediaFile getFileForCurrentUser(Long fileId, User user) {
        return mediaFileRepository.findById(fileId)
                .filter(file -> file.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new EntityNotFoundException(fileId.toString(), String.format("File %d not found", fileId)));
    }

    public void deleteFileForCurrentUser(Long fileId, User user) throws IOException {
        MediaFile file = getFileForCurrentUser(fileId, user);
        Path path = Paths.get(file.getStorageFilename());
        Files.deleteIfExists(path);
        mediaFileRepository.delete(file);
    }

}
