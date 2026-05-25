package ru.tbcarus.photocloudserver.service.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsRequest;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsResponse;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChecksumSyncService {

    private final StoredObjectRepository storedObjectRepository;
    private final ChecksumExistsProperties checksumExistsProperties;

    public ChecksumExistsResponse checkExisting(User user, ChecksumExistsRequest request) {
        if (request.checksums().size() > checksumExistsProperties.getMaxBatchSize()) {
            throw new IllegalArgumentException("Checksum batch size must be at most " + checksumExistsProperties.getMaxBatchSize());
        }

        // Дубликаты не нужно отправлять в БД повторно, но порядок ответа должен оставаться предсказуемым.
        LinkedHashSet<String> requestedChecksums = new LinkedHashSet<>();
        for (String checksum : request.checksums()) {
            requestedChecksums.add(checksum.toLowerCase(Locale.ROOT));
        }

        Set<String> existingChecksums = storedObjectRepository.findExistingChecksums(user.getId(), requestedChecksums);
        List<String> existing = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String checksum : requestedChecksums) {
            if (existingChecksums.contains(checksum)) {
                existing.add(checksum);
            } else {
                missing.add(checksum);
            }
        }

        // TODO: Добавить отдельный link-existing endpoint для создания FileItem без повторной загрузки байтов.
        // TODO: Device/deviceId и full sync sessions понадобятся для first sync и восстановления после переустановки.
        // TODO: Расширенный response с fileType/size/capturedAt лучше вынести в отдельный endpoint, оставив этот минимальным.
        return new ChecksumExistsResponse(existing, missing);
    }
}
