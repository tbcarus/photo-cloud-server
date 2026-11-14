package ru.tbcarus.photocloudserver.service.upload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tbcarus.photocloudserver.model.UploadSession;
import ru.tbcarus.photocloudserver.repository.UploadSessionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;

@Component
@RequiredArgsConstructor
public class UploadPublicationRecovery {
    private final UploadSessionRepository sessions;
    private final UploadStorage storage;
    private final PlatformTransactionManager transactionManager;

    // Вызывается под блокировкой ROOT до любого изменения путей владельца.
    // Нужна отдельная фиксация: последующая операция Library вправе откатить свою транзакцию.
    public void recover(Long userId) {
        if (sessions.findByUserIdAndPublicationPathIsNotNull(userId).isEmpty()) return;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            for (UploadSession session : sessions.findByUserIdAndPublicationPathIsNotNull(userId)) {
                try {
                    if (session.getState() != UploadSession.State.COMPLETED) storage.undoPublication(session);
                    // Отмена может фиксироваться без ROOT: не перезаписываем её состояние старым снимком.
                    sessions.clearPublicationPath(session.getId());
                } catch (IOException ex) {
                    throw new UncheckedIOException("Cannot recover upload publication", ex);
                }
            }
        });
    }
}
