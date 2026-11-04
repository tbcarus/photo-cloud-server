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
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsRequest;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsResponse;
import ru.tbcarus.photocloudserver.model.dto.CopyFileRequest;
import ru.tbcarus.photocloudserver.model.dto.FileChecksumDto;
import ru.tbcarus.photocloudserver.model.dto.FileItemDto;
import ru.tbcarus.photocloudserver.model.dto.MoveFileRequest;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.RenameFileRequest;
import ru.tbcarus.photocloudserver.service.FileItemService;
import ru.tbcarus.photocloudserver.service.sync.ChecksumSyncService;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
    public static final String THUMBNAIL_URL = FILE_ID_URL + "/thumbnail";
    public static final String MOVE_URL = FILE_ID_URL + "/move";
    public static final String COPY_URL = FILE_ID_URL + "/copy";
    public static final String CHECKSUMS_URL = "/checksums";
    public static final String CHECKSUMS_EXISTS_URL = CHECKSUMS_URL + "/exists";
    private static final String SORT_BY_NAME = "name";
    private static final String SORT_NAME_EXPRESSION = "sql('lower(? collate \"ru-x-icu\")', f.originalName)";

    private final FileItemService fileItemService;
    private final ChecksumSyncService checksumSyncService;
    private final ThumbnailService thumbnailService;

    // Старый upload endpoint сохраняется для совместимости; folderId можно передать как optional multipart-параметр.
    @Operation(summary = "Upload file")
    @PostMapping
    public ResponseEntity<FileItemDto> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @RequestParam(value = "backupSource", required = false) UUID backupSource,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.uploadFile(file, user, folderId, backupSource));
    }

    // Явный upload endpoint делает новый контракт заметным, но использует тот же pipeline.
    @Operation(summary = "Upload file")
    @PostMapping(UPLOAD_URL)
    public ResponseEntity<FileItemDto> uploadFileExplicit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @RequestParam(value = "backupSource", required = false) UUID backupSource,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.uploadFile(file, user, folderId, backupSource));
    }

    // Возвращает файлы пользователя; при folderId список ограничен только прямыми файлами этой папки.
    @Operation(summary = "Get current user files")
    @GetMapping
    public PageResponse<FileItemDto> getUserFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(page, size, resolveFilesSort(sort));
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

    // Порядок применяет PostgreSQL до LIMIT/OFFSET; id делает порядок страниц однозначным.
    private Sort resolveFilesSort(String sort) {
        if (sort == null) {
            return Sort.by(
                    Sort.Order.desc("capturedAt"),
                    Sort.Order.desc("uploadedAt"),
                    Sort.Order.desc("id")
            );
        }
        if (SORT_BY_NAME.equals(sort)) {
            // COLLATE внутри lower(): регистр и алфавит не зависят от локали БД.
            return JpaSort.unsafe(Sort.Direction.ASC, SORT_NAME_EXPRESSION).and(Sort.by(Sort.Order.asc("id")));
        }
        throw new IllegalArgumentException("Unsupported sort value");
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
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(file.getOriginalName()))
                .body(resource);
    }

    // Владелец проверяется до сравнения ETag: 304 не раскрывает существование чужого файла.
    // При совпадении If-None-Match оригинал не декодируется.
    @Operation(summary = "Get image thumbnail")
    @GetMapping(THUMBNAIL_URL)
    public ResponseEntity<byte[]> getThumbnail(@PathVariable Long id,
                                               @RequestHeader HttpHeaders headers,
                                               @AuthenticationPrincipal User user) throws IOException {
        FileItem file = fileItemService.getFileForCurrentUser(id, user);
        thumbnailService.requireSupported(file);
        String etag = thumbnailService.etag(file);
        if (matches(headers.getIfNoneMatch(), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(thumbnailService.cacheControl())
                    .build();
        }
        byte[] jpeg = thumbnailService.generate(file);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .eTag(etag)
                .cacheControl(thumbnailService.cacheControl())
                .body(jpeg);
    }

    // Слабое сравнение, как требует RFC 9110 для If-None-Match. «*» не совпадает: миниатюры может не быть
    // (например, у анимированного WebP), поэтому такой запрос получает обычный ответ.
    private static boolean matches(List<String> ifNoneMatch, String etag) {
        for (String candidate : ifNoneMatch) {
            String value = candidate.startsWith("W/") ? candidate.substring(2) : candidate;
            if (etag.equals(value)) {
                return true;
            }
        }
        return false;
    }

    // ASCII-имя сохраняет прежний вид filename="..."; остальные кодируются по RFC 5987:
    // Tomcat отбрасывает заголовок с символами вне ISO-8859-1.
    private String attachment(String filename) {
        boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(filename);
        return ContentDisposition.attachment()
                .filename(filename, ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    // Переименовывает FileItem и физический файл библиотеки; в Camera физическое имя задано ключом и не меняется.
    @Operation(summary = "Rename file")
    @PatchMapping(FILE_ID_URL)
    public ResponseEntity<FileItemDto> renameFile(
            @PathVariable Long id,
            @RequestBody @Valid RenameFileRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.renameFileForCurrentUser(id, request, user));
    }

    // Перемещает файл в другую папку вместе с физическим файлом.
    @Operation(summary = "Move file")
    @PostMapping(MOVE_URL)
    public ResponseEntity<FileItemDto> moveFile(
            @PathVariable Long id,
            @RequestBody @Valid MoveFileRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(fileItemService.moveFileForCurrentUser(id, request, user));
    }

    // Создаёт независимую физическую копию в дереве целевой папки и новые StoredObject и FileItem.
    @Operation(summary = "Copy file")
    @PostMapping(COPY_URL)
    public ResponseEntity<FileItemDto> copyFile(
            @PathVariable Long id,
            @RequestBody @Valid CopyFileRequest request,
            @AuthenticationPrincipal User user) throws IOException {
        return ResponseEntity.ok(fileItemService.copyFileForCurrentUser(id, request, user));
    }

    // Удаляет FileItem; владелец StoredObject удаляет также все ссылки и StoredObject, байты убирает фоновая очистка.
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

    // Ответ намеренно минимальный: клиенту на этом этапе нужно только решение "загружать / не загружать".
    @Operation(summary = "Check existing checksums")
    @PostMapping(CHECKSUMS_EXISTS_URL)
    public ResponseEntity<ChecksumExistsResponse> checkExistingChecksums(
            @RequestBody @Valid ChecksumExistsRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(checksumSyncService.checkExisting(user, request));
    }
}
