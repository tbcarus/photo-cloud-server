package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.exception.PublicLinkNotFoundException;
import ru.tbcarus.photocloudserver.model.FileItem;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.StoredObject;
import ru.tbcarus.photocloudserver.model.dto.PageResponse;
import ru.tbcarus.photocloudserver.model.dto.PublicFileDto;
import ru.tbcarus.photocloudserver.model.dto.PublicFolderDto;
import ru.tbcarus.photocloudserver.model.dto.PublicResourceDto;
import ru.tbcarus.photocloudserver.model.dto.ShareResourceType;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.repository.FolderRepository;
import ru.tbcarus.photocloudserver.service.FileItemService;
import ru.tbcarus.photocloudserver.service.link.LinkAccess;
import ru.tbcarus.photocloudserver.service.thumbnail.ThumbnailService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Чтение по публичной ссылке без JWT: только GET (и автоматический HEAD). Право проверяет LinkAccess на каждом
 * запросе, в том числе на каждом Range-запросе; байты, миниатюры и Range — те же механизмы, что у владельца.
 * Защитные заголовки ответов ставит PublicResponseHeadersFilter.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Public links (anonymous)")
public class PublicLinkController {

    public static final String DIRECT_URL = "/s/{token}";
    // CSS и JS страницы папки: static/s/assets, отдаются обычной раздачей статики Spring Boot.
    public static final String ASSETS_URL = "/s/assets/**";
    public static final String BASE_URL = ApiPaths.API_V1 + "/public/s/{token}";
    public static final String CHILDREN_URL = BASE_URL + "/folders/{id}/children";
    public static final String FILES_URL = BASE_URL + "/files";
    public static final String DOWNLOAD_URL = FILES_URL + "/{id}/download";
    public static final String VIEW_URL = FILES_URL + "/{id}/view";
    public static final String THUMBNAIL_URL = FILES_URL + "/{id}/thumbnail";

    // В браузере показываются только изображения, которые нельзя исполнить; остальное (в том числе HTML и SVG) скачивается.
    private static final Set<String> INLINE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final Resource FOLDER_PAGE = new ClassPathResource("public-link/folder.html");
    private static final Resource NOT_FOUND_PAGE = new ClassPathResource("public-link/not-found.html");

    private final LinkAccess linkAccess;
    private final FolderRepository folderRepository;
    private final FileItemRepository fileItemRepository;
    private final FileItemService fileItemService;
    private final ThumbnailService thumbnailService;

    /**
     * Прямая ссылка. Файл: изображение JPEG/PNG/WebP — inline, остальное — attachment, с Range.
     * Папка: статическая страница просмотра; содержимое она получает через публичные API ниже, каждое из которых
     * снова проверяет ссылку. Отказ браузеру (Accept: text/html) — простая HTML-страница 404, остальным — JSON.
     */
    @Operation(summary = "Open public link")
    @GetMapping(DIRECT_URL)
    public ResponseEntity<?> open(@PathVariable String token,
                                  @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) throws IOException {
        LinkAccess.Grant grant;
        try {
            grant = linkAccess.resolve(token);
        } catch (PublicLinkNotFoundException e) {
            if (accept != null && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_HTML_VALUE)) {
                return page(HttpStatus.NOT_FOUND, NOT_FOUND_PAGE);
            }
            throw e;
        }
        if (grant.file() != null) {
            return serve(grant.file(), true);
        }
        return page(HttpStatus.OK, FOLDER_PAGE);
    }

    @Operation(summary = "Describe public link target")
    @GetMapping(BASE_URL)
    public PublicResourceDto describe(@PathVariable String token) {
        return describe(linkAccess.resolve(token));
    }

    @Operation(summary = "Public folder children")
    @GetMapping(CHILDREN_URL)
    public List<PublicFolderDto> children(@PathVariable String token, @PathVariable Long id) {
        Folder folder = linkAccess.readableFolder(token, id);
        return folderRepository.findChildrenByUserIdAndParentId(folder.getUser().getId(), folder.getId()).stream()
                .map(child -> new PublicFolderDto(child.getId(), child.getName()))
                .toList();
    }

    /** Прямые файлы папки поддерева ссылки; страницы и sort — как у {@code GET /api/v1/files}. */
    @Operation(summary = "Public folder files")
    @GetMapping(FILES_URL)
    public PageResponse<PublicFileDto> files(@PathVariable String token,
                                             @RequestParam Long folderId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(required = false) String sort) {
        Folder folder = linkAccess.readableFolder(token, folderId);
        PageRequest pageable = PageRequest.of(page, size, FileController.resolveFilesSort(sort));
        return PageResponse.of(fileItemRepository.findAllByUserIdAndFolderId(folder.getUser().getId(), folder.getId(), pageable)
                .map(PublicLinkController::toDto));
    }

    @Operation(summary = "Public download")
    @GetMapping(DOWNLOAD_URL)
    public ResponseEntity<Resource> download(@PathVariable String token, @PathVariable Long id) throws IOException {
        return serve(linkAccess.readableFile(token, id), false);
    }

    /** Просмотр в браузере: безопасные изображения inline, остальное — как download. */
    @Operation(summary = "Public view in browser")
    @GetMapping(VIEW_URL)
    public ResponseEntity<Resource> view(@PathVariable String token, @PathVariable Long id) throws IOException {
        return serve(linkAccess.readableFile(token, id), true);
    }

    // Право проверяется до сравнения ETag: 304 не раскрывает файл вне ссылки.
    @Operation(summary = "Public thumbnail")
    @GetMapping(THUMBNAIL_URL)
    public ResponseEntity<byte[]> thumbnail(@PathVariable String token, @PathVariable Long id,
                                            @RequestHeader HttpHeaders headers) throws IOException {
        FileItem file = linkAccess.readableFile(token, id);
        thumbnailService.requireSupported(file);
        String etag = thumbnailService.etag(file);
        if (FileController.matches(headers.getIfNoneMatch(), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .eTag(etag)
                .body(thumbnailService.generate(file));
    }

    // Страницы не содержат данных пользователя: имена выводит скрипт как текст, поэтому HTML можно отдавать как есть.
    private static ResponseEntity<Resource> page(HttpStatus status, Resource page) {
        return ResponseEntity.status(status).contentType(HTML).body(page);
    }

    private PublicResourceDto describe(LinkAccess.Grant grant) {
        if (grant.folder() != null) {
            return new PublicResourceDto(ShareResourceType.FOLDER, grant.folder().getId(), grant.folder().getName(), null,
                    grant.share().getExpiresAt());
        }
        FileItem file = grant.file();
        return new PublicResourceDto(ShareResourceType.FILE, file.getId(), file.getOriginalName(), toDto(file),
                grant.share().getExpiresAt());
    }

    // Тело — Resource без буфера: Range/206 обслуживает Spring MVC, как у download владельца.
    private ResponseEntity<Resource> serve(FileItem file, boolean inlineIfSafe) throws IOException {
        Resource resource = fileItemService.getDownloadResource(file);
        String mimeType = file.getStoredObject().getDetectedMimeType();
        boolean inline = inlineIfSafe && INLINE_TYPES.contains(mimeType);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_DISPOSITION, inline ? inline(file.getOriginalName()) : FileController.attachment(file.getOriginalName()))
                .body(resource);
    }

    private static String inline(String filename) {
        boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(filename);
        return ContentDisposition.inline()
                .filename(filename, ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private static PublicFileDto toDto(FileItem file) {
        StoredObject stored = file.getStoredObject();
        return new PublicFileDto(file.getId(), file.getOriginalName(), stored.getDetectedMimeType(), stored.getSize(),
                stored.getFileType() == null ? null : stored.getFileType().name());
    }
}
