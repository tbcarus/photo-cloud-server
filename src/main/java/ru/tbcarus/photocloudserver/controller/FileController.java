package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
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
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.service.FileItemService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(FileController.BASE_URL)
@Tag(name = "Files processing")
public class FileController {
    // Контроллер оставляет наружу только логическую FileItem-модель без storageKey.
    public static final String BASE_URL = ApiPaths.API_V1 + "/files";
    public static final String FILE_ID_URL = "/{id}";
    public static final String DOWNLOAD_URL = FILE_ID_URL + "/download";
    public static final String CHECKSUMS_URL = "/checksums";

    private final FileItemService fileItemService;

    @Operation(summary = "Upload file")
    @PostMapping
    public ResponseEntity<FileItemDto> uploadFile(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.uploadFile(file, user));
    }

    @Operation(summary = "Get current user files")
    @GetMapping
    public PageResponse<FileItemDto> getUserFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal User user) {
        Sort sort = Sort.by(
                Sort.Order.desc("capturedAt"),
                Sort.Order.desc("uploadedAt"),
                Sort.Order.desc("id")
        );
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<FileItemDto> files = fileItemService.getUserFiles(pageable, user);
        return PageResponse.<FileItemDto>builder()
                .items(files.getContent())
                .page(files.getNumber())
                .size(files.getSize())
                .totalElements(files.getTotalElements())
                .totalPages(files.getTotalPages())
                .hasNext(files.hasNext())
                .hasPrevious(files.hasPrevious())
                .build();
    }

    @Operation(summary = "Get file metadata")
    @GetMapping(FILE_ID_URL)
    public ResponseEntity<FileItemDto> getFile(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.getFileDtoForCurrentUser(id, user));
    }

    @Operation(summary = "Download file")
    @GetMapping(DOWNLOAD_URL)
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id, @AuthenticationPrincipal User user) throws IOException {
        FileItem file = fileItemService.getFileForCurrentUser(id, user);
        Resource resource = fileItemService.getDownloadResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
                .body(resource);
    }

    @Operation(summary = "Delete file")
    @DeleteMapping(FILE_ID_URL)
    public ResponseEntity<Void> deleteFile(@PathVariable Long id, @AuthenticationPrincipal User user) {
        fileItemService.deleteFileForCurrentUser(id, user);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get current user file checksums")
    @GetMapping(CHECKSUMS_URL)
    public ResponseEntity<List<FileChecksumDto>> getChecksums(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.getChecksumsForUser(user.getId()));
    }
}
