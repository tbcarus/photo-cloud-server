package ru.tbcarus.photocloudserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.tbcarus.photocloudserver.model.StoredObject;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Repository
public interface StoredObjectRepository extends JpaRepository<StoredObject, Long> {
    Optional<StoredObject> findFirstByUserIdAndChecksumOrderByIdAsc(Long userId, String checksum);

    @Query("""
            SELECT DISTINCT lower(so.checksum)
            FROM StoredObject so
            WHERE so.user.id = :userId
              AND lower(so.checksum) IN :checksums
            """)
    Set<String> findExistingChecksums(Long userId, Collection<String> checksums);
}
