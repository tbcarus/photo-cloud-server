package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.CreateFolderRequest;
import ru.tbcarus.photocloudserver.model.dto.FolderDto;
import ru.tbcarus.photocloudserver.model.dto.MoveFolderRequest;
import ru.tbcarus.photocloudserver.model.dto.RenameFolderRequest;
import ru.tbcarus.photocloudserver.model.dto.mapper.FolderMapper;
import ru.tbcarus.photocloudserver.service.FolderService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(FolderController.BASE_URL)
@Tag(name = "Folders processing")
public class FolderController {

    public static final String BASE_URL = ApiPaths.API_V1 + "/folders";
    public static final String ROOT_URL = "/root";
    public static final String CHILDREN_URL = "/{id}/children";
    public static final String FOLDER_ID_URL = "/{id}";
    public static final String MOVE_URL = FOLDER_ID_URL + "/move";

    private final FolderService folderService;
    private final FolderMapper folderMapper;

    // Возвращает единственный ROOT текущего пользователя, создавая его при первом обращении.
    @Operation(summary = "Get current user root folder")
    @GetMapping(ROOT_URL)
    public ResponseEntity<FolderDto> getRoot(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderMapper.toDto(folderService.getOrCreateRoot(user)));
    }

    // Возвращает только прямых потомков папки, без рекурсивного дерева.
    @Operation(summary = "Get folder children")
    @GetMapping(CHILDREN_URL)
    public ResponseEntity<List<FolderDto>> getChildren(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderService.getChildren(user, id).stream()
                .map(folderMapper::toDto)
                .toList());
    }

    // Создаёт USER-папку в ROOT, если parentId не передан, либо внутри USER-папки.
    @Operation(summary = "Create folder")
    @PostMapping
    public ResponseEntity<FolderDto> createFolder(
            @Valid @RequestBody CreateFolderRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderMapper.toDto(folderService.createFolder(user, request.parentId(), request.name())));
    }

    // Переименовывает только USER-папку; системные папки сохраняют стабильные имена.
    @Operation(summary = "Rename folder")
    @PatchMapping(FOLDER_ID_URL)
    public ResponseEntity<FolderDto> renameFolder(
            @PathVariable Long id,
            @Valid @RequestBody RenameFolderRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderMapper.toDto(folderService.renameFolder(user, id, request.name())));
    }

    // Перемещает USER-папку и проверяет, что дерево не превращается в цикл.
    @Operation(summary = "Move folder")
    @PostMapping(MOVE_URL)
    public ResponseEntity<FolderDto> moveFolder(
            @PathVariable Long id,
            @Valid @RequestBody MoveFolderRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderMapper.toDto(folderService.moveFolder(user, id, request.targetParentId())));
    }

    // Удаляет только пустую USER-папку; рекурсивное удаление намеренно не выполняется.
    @Operation(summary = "Delete folder")
    @DeleteMapping(FOLDER_ID_URL)
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id, @AuthenticationPrincipal User user) {
        folderService.deleteFolder(user, id);
        return ResponseEntity.noContent().build();
    }
}
