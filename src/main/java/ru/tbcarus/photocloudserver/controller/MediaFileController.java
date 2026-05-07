package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping(MediaFileController.BASE_URL)
@Tag(name = "Media files processing")
public class MediaFileController {
    // Handles upload, listing, checksum checks, download, thumbnail, and deletion of media files.
    public static final String BASE_URL = ApiPaths.API_V1 + "/media";
    public static final String MEDIA_ID_URL = "/{id}";
    public static final String DOWNLOAD_URL = MEDIA_ID_URL + "/download";
    public static final String THUMBNAIL_URL = MEDIA_ID_URL + "/thumbnail";
    public static final String CHECK_EXIST_URL = "/check-exist";
    public static final String CHECKSUMS_EXISTS_URL = "/checksums/exists";
    public static final String CHECKSUMS_URL = "/checksums";

    private final MediaFileService mediaFileService;

    @Operation(summary = "Upload media file")
    @PostMapping
    public ResponseEntity<MediaFileDto> uploadFile(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal User user) throws IOException {
        MediaFileDto uploaded = mediaFileService.uploadFile(file, user);
        return ResponseEntity.ok(uploaded);
    }

    @Operation(summary = "Get current user media files")
    @GetMapping
    public Page<MediaFileDto> getUserFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return mediaFileService.getUserFiles(pageable, user);
    }

    @Operation(summary = "Get media file metadata")
    @GetMapping(MEDIA_ID_URL)
    public ResponseEntity<MediaFileDto> getFile(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(mediaFileService.getFileDtoForCurrentUser(id, user));
    }

    @Operation(summary = "Download media file")
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

    @Operation(summary = "Reserved endpoint for updating media file metadata")
    @PatchMapping(MEDIA_ID_URL)
    public ResponseEntity<Map<String, String>> updateFile(@PathVariable Long id, @AuthenticationPrincipal User user) {
        // TODO: Update media metadata after editable fields and validation rules are defined.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Media metadata update is not implemented yet"));
    }

    @Operation(summary = "Delete media file")
    @DeleteMapping(MEDIA_ID_URL)
    public ResponseEntity<Void> deleteFile(@PathVariable Long id, @AuthenticationPrincipal User user) throws IOException {
        mediaFileService.deleteFileForCurrentUser(id, user);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reserved endpoint for getting media thumbnail")
    @GetMapping(THUMBNAIL_URL)
    public ResponseEntity<Map<String, String>> getThumbnail(@PathVariable Long id, @AuthenticationPrincipal User user) {
        // TODO: Return generated thumbnail resource after thumbnail generation and cache rules are implemented.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Media thumbnail is not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for checking one media checksum")
    @PostMapping(CHECK_EXIST_URL)
    public ResponseEntity<Map<String, String>> checkExist() {
        // TODO: Check one checksum after request/response DTOs are defined.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Single checksum check is not implemented yet"));
    }

    @Operation(summary = "Reserved endpoint for checking media checksum batch")
    @PostMapping(CHECKSUMS_EXISTS_URL)
    public ResponseEntity<Map<String, String>> checkChecksumsExist() {
        // TODO: Check checksum batch and return existence status for every checksum.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Batch checksum check is not implemented yet"));
    }

    @Operation(summary = "Get current user media checksums")
    @GetMapping(CHECKSUMS_URL)
    public ResponseEntity<List<MediaFileChecksumDto>> getChecksums(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(mediaFileService.getChecksumsForUser(user.getId()));
    }

}
