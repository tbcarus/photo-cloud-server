package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.service.FolderService;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// COMMIT выполнен, но клиент получил ошибку: так выглядит потеря ответа PostgreSQL при подтверждённой фиксации.
class FolderCommitOutcomeIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "pass1";

    @MockitoSpyBean
    PlatformTransactionManager transactionManager;

    @Autowired
    FolderService folderService;

    @Test
    void committedFolderKeepsDirectoryWhenCommitReportsError() {
        User user = createUser("outcome@test.local", PASSWORD);
        Long rootId = folderService.getOrCreateRoot(user).getId();
        reportErrorAfterCommit(false);

        assertThatThrownBy(() -> folderService.createFolder(user, rootId, "Saved"))
                .isInstanceOf(TransactionSystemException.class);

        // Запись зафиксирована: каталог нужен ей и остаётся на месте.
        assertThat(folderRepository.findByUserIdAndParentIdAndName(user.getId(), rootId, "Saved")).isPresent();
        assertThat(directory(user, "Saved")).isDirectory();
    }

    @Test
    void directoryIsKeptWhenFolderRecordCannotBeChecked() {
        User user = createUser("unknown@test.local", PASSWORD);
        Long rootId = folderService.getOrCreateRoot(user).getId();
        reportErrorAfterCommit(true);

        assertThatThrownBy(() -> folderService.createFolder(user, rootId, "Unknown"))
                .isInstanceOf(TransactionSystemException.class);

        assertThat(directory(user, "Unknown")).isDirectory();
    }

    // Участвующие транзакции тоже проходят через commit(): ошибка нужна только на настоящей фиксации.
    private void reportErrorAfterCommit(boolean databaseLost) {
        AtomicBoolean commitPending = new AtomicBoolean(true);
        AtomicBoolean checkFails = new AtomicBoolean(false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            TransactionStatus status = invocation.getArgument(0);
            if (status.isNewTransaction() && commitPending.compareAndSet(true, false)) {
                checkFails.set(databaseLost);
                throw new TransactionSystemException("commit outcome is unknown");
            }
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            if (checkFails.compareAndSet(true, false)) {
                throw new CannotCreateTransactionException("database is not available");
            }
            return invocation.callRealMethod();
        }).when(transactionManager).getTransaction(any(TransactionDefinition.class));
    }

    private Path directory(User user, String name) {
        return storageRoot().resolve(user.getId() + "/files/" + name);
    }
}
