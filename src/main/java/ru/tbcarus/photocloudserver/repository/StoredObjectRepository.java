package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.StoredObject;

import java.util.Optional;

@Repository
public interface StoredObjectRepository extends JpaRepository<StoredObject, Long> {
    // TODO: может понадобиться для будущего server-side copy/link-existing,
    // когда нужно найти физический объект пользователя по checksum без учёта папки.
    Optional<StoredObject> findFirstByUserIdAndChecksumOrderByIdAsc(Long userId, String checksum);
}
