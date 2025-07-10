package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.exception.FileNotFoundException;
import ru.tbcarus.photocloudserver.model.MediaFile;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.service.MediaFileService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Media files processing")
public class MediaFileController {
    public static final String UPLOAD_URL = "/api/v1/photos/upload"; // загрузка файлов
    public static final String PHOTOS_URL = "/api/v1/files"; // список фото (пагинация + фильтры)
    public static final String PHOTO_URL = "/api/v1/photos/{id}"; // метаданные фото
    public static final String CHECKSUMS_URL = "/api/v1/media/checksums"; // метаданные фото
    public static final String PHOTO_THUMBNAIL_URL = "/api/v1/photos/{id}/thumbnail"; // миниатюра (кэшировать!).
    public static final String DOWNLOAD_URL = "/api/v1/photos/{id}/download"; // скачивание оригинала
    public static final String ALBUM_URL = "/api/v1/albums";
    public static final String ALBUM_PHOTOS_URL = "/api/v1/albums/{id}/photos";
    // добавить пути:
    // альбомы прикрутить в конце, сначала всё заливать в один
    // изменение альбома для существующей фотографии

    private final MediaFileService mediaFileService;

    @PostMapping(UPLOAD_URL)
    public ResponseEntity<MediaFileDto> uploadFile(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal User user) throws IOException {
        MediaFileDto uploaded = mediaFileService.uploadFile(file, user);
        return ResponseEntity.ok(uploaded);
    }

    @GetMapping(PHOTOS_URL)
    public Page<MediaFileDto> getUserFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return mediaFileService.getUserFiles(pageable, user);
    }

    @GetMapping(DOWNLOAD_URL)
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id, @AuthenticationPrincipal User user) throws IOException {
        MediaFile file = mediaFileService.getFileForCurrentUser(id, user);

        Path path = Paths.get(file.getStorageFilename());
        Resource resource = new UrlResource(path.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            throw new FileNotFoundException(id.toString(), "File not found on disk: " + file.getStorageFilename());
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
                .body(resource);
    }

    @DeleteMapping(PHOTO_URL)
    public ResponseEntity<Void> deleteFile(@PathVariable Long id, @AuthenticationPrincipal User user) throws IOException {
        mediaFileService.deleteFileForCurrentUser(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(CHECKSUMS_URL)
    public ResponseEntity<List<MediaFileChecksumDto>> getChecksums(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(mediaFileService.getChecksumsForUser(user.getId()));
    }

}
