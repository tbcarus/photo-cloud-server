package ru.tbcarus.photocloudserver.service.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.tbcarus.photocloudserver.model.Folder;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.ChecksumExistsRequest;
import ru.tbcarus.photocloudserver.repository.FileItemRepository;
import ru.tbcarus.photocloudserver.service.FolderService;

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

    private FileItemRepository fileItemRepository;
    private FolderService folderService;
    private ChecksumSyncService checksumSyncService;

    @BeforeEach
    void setUp() {
        fileItemRepository = mock(FileItemRepository.class);
        folderService = mock(FolderService.class);
        ChecksumExistsProperties properties = new ChecksumExistsProperties();
        properties.setMaxBatchSize(3);
        checksumSyncService = new ChecksumSyncService(fileItemRepository, folderService, properties);
    }

    @Test
    void checkExistingNormalizesDeduplicatesAndKeepsResponseOrder() {
        User user = User.builder().id(7L).build();
        Folder folder = Folder.builder().id(11L).build();
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        when(folderService.getFolderForUser(eq(11L), eq(user))).thenReturn(folder);
        when(fileItemRepository.findExistingChecksumsInFolder(eq(7L), eq(11L), any())).thenReturn(Set.of(first));

        var response = checksumSyncService.checkExisting(user,
                new ChecksumExistsRequest(11L, List.of(first.toUpperCase(), first, second)));

        assertThat(response.existing()).containsExactly(first);
        assertThat(response.missing()).containsExactly(second);
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        org.mockito.Mockito.verify(fileItemRepository).findExistingChecksumsInFolder(eq(7L), eq(11L), captor.capture());
        assertThat(captor.getValue()).containsExactly(first, second);
    }

    @Test
    void checkExistingRejectsBatchOverLimitBeforeFolderAndRepositoryCalls() {
        User user = User.builder().id(7L).build();
        ChecksumExistsProperties properties = new ChecksumExistsProperties();
        properties.setMaxBatchSize(2);
        checksumSyncService = new ChecksumSyncService(fileItemRepository, folderService, properties);

        assertThatThrownBy(() -> checksumSyncService.checkExisting(user,
                new ChecksumExistsRequest(11L, List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2");

        org.mockito.Mockito.verifyNoInteractions(folderService, fileItemRepository);
    }
}
