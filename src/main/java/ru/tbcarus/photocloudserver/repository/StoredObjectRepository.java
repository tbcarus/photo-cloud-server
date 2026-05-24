package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.StoredObject;

import java.util.Optional;

@Repository
public interface StoredObjectRepository extends JpaRepository<StoredObject, Long> {
    Optional<StoredObject> findFirstByUserIdAndChecksumOrderByIdAsc(Long userId, String checksum);
}
