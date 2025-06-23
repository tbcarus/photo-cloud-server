package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.MediaFileDto;
import ru.tbcarus.photocloudserver.service.MediaFileService;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@Tag(name = "Media files processing")
public class MediaFileController {
    public static final String UPLOAD_URL = "/api/v1/photos/upload"; // загрузка файлов
    public static final String PHOTOS_URL = "/api/v1/photos"; // список фото (пагинация + фильтры)
    public static final String PHOTO_URL = "/api/v1/photos/{id}"; // метаданные фото
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


}
