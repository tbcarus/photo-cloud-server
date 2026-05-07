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
import ru.tbcarus.photocloudserver.controller.MediaFileController;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.MediaType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.model.dto.mapper.MediaFileMapper;
import ru.tbcarus.photocloudserver.repository.MediaFileRepository;
import ru.tbcarus.photocloudserver.util.FileUtils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.tbcarus.photocloudserver.model.dto.MediaFileResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaFileService {
    // Stores media files and exposes current media-related business operations.

    private static final String MEDIA_BASE_PATH = MediaFileController.BASE_URL + "/";

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

        byte[] fileBytes = file.getBytes();
        String checksum = FileUtils.calculateSHA256(fileBytes);

        String originalFilename = file.getOriginalFilename();
        String extension = FilenameUtils.getExtension(originalFilename);
        String storageFilename = originalFilename + "." + UUID.randomUUID() + "." + extension;

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
                .checksum(checksum)
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

    public MediaFileDto getFileDtoForCurrentUser(Long fileId, User user) {
        return mediaFileMapper.toDto(getFileForCurrentUser(fileId, user));
    }

    public void deleteFileForCurrentUser(Long fileId, User user) throws IOException {
        MediaFile file = getFileForCurrentUser(fileId, user);
        Path path = Paths.get(file.getStorageFilename());
        Files.deleteIfExists(path);
        mediaFileRepository.delete(file);
    }

    public List<MediaFileChecksumDto> getChecksumsForUser(Long userId) {
        return mediaFileRepository.findAllChecksumsAndOriginalFilenamesByUserId(userId);
    }

    public MediaFileResponse toResponse(MediaFile mediaFile, HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("")
                .build()
                .toUriString();
        MediaFileResponse response = mediaFileMapper.toResponse(mediaFile);
        response.setUrl(baseUrl + MEDIA_BASE_PATH + mediaFile.getId() + "/download");
        response.setThumbnailUrl(mediaFile.getThumbnailPath() != null
                ? baseUrl + MEDIA_BASE_PATH + mediaFile.getId() + "/thumbnail"
                : null);
        return response;
    }
}
