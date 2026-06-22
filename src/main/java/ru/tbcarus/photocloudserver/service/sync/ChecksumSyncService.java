package ru.tbcarus.photocloudserver.service.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsRequest;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsResponse;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.service.FolderService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChecksumSyncService {

    private final FileItemRepository fileItemRepository;
    private final FolderService folderService;
    private final ChecksumExistsProperties checksumExistsProperties;

    public ChecksumExistsResponse checkExisting(User user, ChecksumExistsRequest request) {
        if (request.checksums().size() > checksumExistsProperties.getMaxBatchSize()) {
            throw new IllegalArgumentException("Checksum batch size must be at most " + checksumExistsProperties.getMaxBatchSize());
        }

        // Проверяем владение папкой: чужая/несуществующая папка отдаёт 404, как и остальной files API.
        Folder folder = folderService.getFolderForUser(request.folderId(), user);

        // Дубликаты не нужно отправлять в БД повторно, но порядок ответа должен оставаться предсказуемым.
        LinkedHashSet<String> requestedChecksums = new LinkedHashSet<>();
        for (String checksum : request.checksums()) {
            requestedChecksums.add(checksum.toLowerCase(Locale.ROOT));
        }

        // existing = в этой папке пользователя уже есть FileItem с таким checksum.
        // Тот же checksum в другой папке или у другого пользователя на результат не влияет.
        Set<String> existingChecksums = fileItemRepository.findExistingChecksumsInFolder(
                user.getId(), folder.getId(), requestedChecksums);
        List<String> existing = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String checksum : requestedChecksums) {
            if (existingChecksums.contains(checksum)) {
                existing.add(checksum);
            } else {
                missing.add(checksum);
            }
        }

        // TODO: server-side copy без передачи байтов для ручной загрузки в другую папку — отдельный будущий endpoint/этап.
        return new ChecksumExistsResponse(existing, missing);
    }
}
