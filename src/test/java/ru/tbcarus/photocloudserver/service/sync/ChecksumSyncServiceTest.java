package ru.tbcarus.photocloudserver.service.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsRequest;
import ru.tbcarus.photocloudserver.repository.StoredObjectRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChecksumSyncServiceTest {

    private StoredObjectRepository storedObjectRepository;
    private ChecksumSyncService checksumSyncService;

    @BeforeEach
    void setUp() {
        storedObjectRepository = mock(StoredObjectRepository.class);
        ChecksumExistsProperties properties = new ChecksumExistsProperties();
        properties.setMaxBatchSize(3);
        checksumSyncService = new ChecksumSyncService(storedObjectRepository, properties);
    }

    @Test
    void checkExistingNormalizesDeduplicatesAndKeepsResponseOrder() {
        User user = User.builder().id(7L).build();
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        when(storedObjectRepository.findExistingChecksums(eq(7L), any())).thenReturn(Set.of(first));

        var response = checksumSyncService.checkExisting(user, new ChecksumExistsRequest(List.of(first.toUpperCase(), first, second)));

        assertThat(response.existing()).containsExactly(first);
        assertThat(response.missing()).containsExactly(second);
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        org.mockito.Mockito.verify(storedObjectRepository).findExistingChecksums(eq(7L), captor.capture());
        assertThat(captor.getValue()).containsExactly(first, second);
    }

    @Test
    void checkExistingRejectsBatchOverLimitBeforeRepositoryCall() {
        User user = User.builder().id(7L).build();
        ChecksumExistsProperties properties = new ChecksumExistsProperties();
        properties.setMaxBatchSize(2);
        checksumSyncService = new ChecksumSyncService(storedObjectRepository, properties);

        assertThatThrownBy(() -> checksumSyncService.checkExisting(user,
                new ChecksumExistsRequest(List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2");

        org.mockito.Mockito.verifyNoInteractions(storedObjectRepository);
    }
}
