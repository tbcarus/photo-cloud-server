package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import ru.tbcarus.photocloudserver.model.dto.CopyFileRequest;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.MoveFileRequest;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.RenameFileRequest;
import ru.tbcarus.photocloudserver.service.FileItemService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(FileController.BASE_URL)
@Tag(name = "Files processing")
public class FileController {
    // Контроллер оставляет наружу только логическую FileItem-модель без данных физического хранения.
    public static final String BASE_URL = ApiPaths.API_V1 + "/files";
    public static final String FILE_ID_URL = "/{id}";
    public static final String UPLOAD_URL = "/upload";
    public static final String DOWNLOAD_URL = FILE_ID_URL + "/download";
    public static final String MOVE_URL = FILE_ID_URL + "/move";
    public static final String COPY_URL = FILE_ID_URL + "/copy";
    public static final String CHECKSUMS_URL = "/checksums";

    private final FileItemService fileItemService;

    // Старый upload endpoint сохраняется для совместимости; folderId можно передать как optional multipart-параметр.
    @Operation(summary = "Upload file")
    @PostMapping
    public ResponseEntity<FileItemDto> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.uploadFile(file, user, folderId));
    }

    // Явный upload endpoint делает новый контракт заметным, но использует тот же pipeline.
    @Operation(summary = "Upload file")
    @PostMapping(UPLOAD_URL)
    public ResponseEntity<FileItemDto> uploadFileExplicit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.uploadFile(file, user, folderId));
    }

    // Возвращает файлы пользователя; при folderId список ограничен только прямыми файлами этой папки.
    @Operation(summary = "Get current user files")
    @GetMapping
    public PageResponse<FileItemDto> getUserFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long folderId,
            @AuthenticationPrincipal User user) {
        Sort sort = Sort.by(
                Sort.Order.desc("capturedAt"),
                Sort.Order.desc("uploadedAt"),
                Sort.Order.desc("id")
        );
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<FileItemDto> files = fileItemService.getUserFiles(pageable, user, folderId);
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

    // Возвращает логическую карточку файла без раскрытия физических путей StoredObject.
    @Operation(summary = "Get file metadata")
    @GetMapping(FILE_ID_URL)
    public ResponseEntity<FileItemDto> getFile(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.getFileDtoForCurrentUser(id, user));
    }

    // Скачивает физический объект, но имя отдаёт логическое originalName из FileItem.
    @Operation(summary = "Download file")
    @GetMapping(DOWNLOAD_URL)
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id, @AuthenticationPrincipal User user) throws IOException {
        FileItem file = fileItemService.getFileForCurrentUser(id, user);
        Resource resource = fileItemService.getDownloadResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getStoredObject().getDetectedMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalName() + "\"")
                .body(resource);
    }

    // Переименовывает только FileItem.originalName; физическое имя в object storage не меняется.
    @Operation(summary = "Rename file")
    @PatchMapping(FILE_ID_URL)
    public ResponseEntity<FileItemDto> renameFile(
            @PathVariable Long id,
            @RequestBody @Valid RenameFileRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.renameFileForCurrentUser(id, request, user));
    }

    // Перемещает логическую запись в другую папку без перемещения физического файла.
    @Operation(summary = "Move file")
    @PostMapping(MOVE_URL)
    public ResponseEntity<FileItemDto> moveFile(
            @PathVariable Long id,
            @RequestBody @Valid MoveFileRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.moveFileForCurrentUser(id, request, user));
    }

    // Создаёт независимую физическую копию StoredObject и новый FileItem.
    @Operation(summary = "Copy file")
    @PostMapping(COPY_URL)
    public ResponseEntity<FileItemDto> copyFile(
            @PathVariable Long id,
            @RequestBody @Valid CopyFileRequest request,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.copyFileForCurrentUser(id, request, user));
    }

    // Удаляет FileItem; владелец StoredObject удаляет также все ссылки, StoredObject и физический файл.
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
