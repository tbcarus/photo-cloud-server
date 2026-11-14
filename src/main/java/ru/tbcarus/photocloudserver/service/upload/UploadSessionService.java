package ru.tbcarus.photocloudserver.service.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tbcarus.photocloudserver.exception.*;
import ru.tbcarus.photocloudserver.model.UploadSession;
import ru.tbcarus.photocloudserver.model.UploadSession.State;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.repository.UploadSessionRepository;
import ru.tbcarus.photocloudserver.repository.UserRepository;
import ru.tbcarus.photocloudserver.service.FileItemService;
import ru.tbcarus.photocloudserver.service.FolderService;
import ru.tbcarus.photocloudserver.service.storage.StorageProperties;
import ru.tbcarus.photocloudserver.util.FileUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadSessionService {
    private final UploadSessionRepository sessions;
    private final UserRepository users;
    private final UploadProperties properties;
    private final StorageProperties storageProperties;
    private final UploadStorage storage;
    private final UploadSessionLocks locks;
    private final FileItemService files;
    private final FolderService folders;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper;
    private Semaphore receivers;
    private final AtomicBoolean finalizerBusy = new AtomicBoolean();
    private final ExecutorService finalizer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "upload-finalizer");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void initialize() { receivers = new Semaphore(properties.getMaxConcurrentChunks()); }

    @PreDestroy
    void stop() { finalizer.shutdownNow(); }

    // Короткая сериализация создания гарантирует общий лимит резервирования и идемпотентность в одном Server.
    public synchronized UploadSessionDto create(CreateUploadSessionRequest request, User user) {
        Optional<UploadSession> previous = sessions.findByUserIdAndClientRequestId(user.getId(), request.clientRequestId());
        if (previous.isPresent()) {
            UploadSession session = previous.get();
            if (!Objects.equals(session.getFolderId(), request.folderId())
                    || !Objects.equals(session.getOriginalFilename(), request.originalFilename())
                    || session.getSize() != request.size()
                    || !session.getChecksum().equalsIgnoreCase(request.checksum())
                    || !Objects.equals(session.getBackupSource(), request.backupSource())) {
                throw conflict("REQUEST_MISMATCH");
            }
            return dto(session);
        }
        if (request.size() <= storageProperties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("Use multipart for files within storage.max-file-size-bytes");
        }
        if (request.size() > properties.getMaxFileSizeBytes()) {
            throw new FileSizeLimitExceededException(properties.getMaxFileSizeBytes());
        }
        files.validateUploadTarget(user, request.folderId(), request.originalFilename(), request.backupSource());
        if (request.size() > properties.getMaxReservedBytes() - sessions.reservedBytes()) {
            throw new UploadSessionException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_CAPACITY_EXCEEDED");
        }
        UploadSession session = new UploadSession();
        session.setId(UUID.randomUUID());
        session.setUserId(user.getId());
        session.setClientRequestId(request.clientRequestId());
        session.setFolderId(request.folderId());
        session.setOriginalFilename(request.originalFilename());
        session.setSize(request.size());
        session.setChecksum(request.checksum().toLowerCase(Locale.ROOT));
        session.setBackupSource(request.backupSource());
        session.setState(State.UPLOADING);
        session.setCreatedAt(now());
        session.setLastActivityAt(session.getCreatedAt());
        return dto(sessions.saveAndFlush(session));
    }

    public UploadSessionDto get(UUID id, User user) { return dto(owned(id, user)); }

    public UploadSessionDto patch(UUID id, User user, long offset, long contentLength, InputStream input) throws IOException {
        owned(id, user);
        acquire(id);
        boolean receiving = false;
        try {
            UploadSession session = owned(id, user);
            requireUploading(session);
            if (offset != session.getConfirmedOffset()) throw conflict("OFFSET_MISMATCH");
            long maximum = Math.min(properties.getMaxChunkSizeBytes(), session.getSize() - offset);
            if (maximum == 0) throw conflict("UPLOAD_ALREADY_FULL");
            if (contentLength > maximum) throw new FileSizeLimitExceededException(maximum);
            if (contentLength == 0) throw new IllegalArgumentException("Empty chunk");
            receiving = receivers.tryAcquire();
            if (!receiving) throw new UploadSessionException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_BUSY");
            if (!reconcile(session)) throw conflict("STORAGE_INCONSISTENT");
            long length = storage.receive(id, input, contentLength, maximum);
            storage.append(session);
            session.setConfirmedOffset(offset + length);
            session.setLastActivityAt(now());
            // force(true) завершён до фиксации offset. Неизвестный исход COMMIT выясняется следующим GET.
            return dto(sessions.saveAndFlush(session));
        } finally {
            if (receiving) {
                try { storage.discardChunk(id); }
                catch (IOException ex) { log.warn("Повторим удаление блока {}", id, ex); }
                receivers.release();
            }
            locks.release(id);
        }
    }

    public UploadSessionDto complete(UUID id, User user) {
        UploadSession current = owned(id, user);
        if (current.getState() != State.UPLOADING) return dto(current);
        acquire(id);
        try {
            UploadSession session = owned(id, user);
            if (session.getState() != State.UPLOADING) return dto(session);
            requireUploading(session);
            if (session.getConfirmedOffset() != session.getSize()) throw conflict("UPLOAD_INCOMPLETE");
            session.setState(State.FINALIZING);
            session.setLastActivityAt(now());
            return dto(sessions.saveAndFlush(session));
        } finally { locks.release(id); }
    }

    public UploadSessionDto cancel(UUID id, User user) {
        UploadSession current = owned(id, user);
        if (current.getState() == State.COMPLETED) throw conflict("UPLOAD_COMPLETED");
        acquire(id);
        try {
            UploadSession session = owned(id, user);
            if (session.getState() == State.COMPLETED) throw conflict("UPLOAD_COMPLETED");
            if (!session.terminal()) finish(session, State.CANCELLED, null);
            cleanup(session);
            return dto(sessions.findById(id).orElseThrow());
        } finally { locks.release(id); }
    }

    @Scheduled(fixedDelayString = "${upload.maintenance-interval}")
    public void maintain() {
        for (int page = 0; ; page++) {
            List<UUID> batch = sessions.sessionIds(PageRequest.of(page, 100));
            if (batch.isEmpty()) break;
            for (UUID id : batch) maintainSession(id);
        }
        submitFinalization();
    }

    private void maintainSession(UUID id) {
        if (!locks.acquire(id)) return;
        try {
            UploadSession session = sessions.findById(id).orElse(null);
            if (session == null) return;
            if (!session.terminal() && session.getLastActivityAt().plus(properties.getInactivityTtl()).isBefore(Instant.now())) {
                finish(session, State.EXPIRED, "UPLOAD_EXPIRED");
            }
            if (session.terminal()) {
                cleanup(session);
                UploadSession latest = sessions.findById(id).orElseThrow();
                if (latest.isCleaned() && latest.getFinishedAt().plus(properties.getResultTtl()).isBefore(Instant.now())) {
                    sessions.deleteById(id);
                }
            } else if (session.getState() == State.UPLOADING) {
                reconcile(session);
                storage.discardChunk(id);
            }
        } catch (Exception ex) {
            log.warn("Повторим обслуживание сессии {}", id, ex);
        } finally { locks.release(id); }
    }

    private void submitFinalization() {
        if (!finalizerBusy.compareAndSet(false, true)) return;
        finalizer.submit(() -> {
            try {
                for (int page = 0; !Thread.currentThread().isInterrupted(); page++) {
                    List<UUID> batch = sessions.finalizingIds(PageRequest.of(page, 100));
                    if (batch.isEmpty()) break;
                    for (UUID id : batch) {
                        if (Thread.currentThread().isInterrupted()) return;
                        finalizeSession(id);
                    }
                }
            }
            finally { finalizerBusy.set(false); }
        });
    }

    // Устойчивая очередь — FINALIZING в БД. После перезапуска хеширование и регистрация повторяются.
    public void finalizeSession(UUID id) {
        if (!locks.acquire(id)) return;
        try {
            UploadSession session = sessions.findById(id).orElseThrow();
            if (session.getState() != State.FINALIZING) return;
            User user = users.findById(session.getUserId()).orElseThrow();
            // Сначала убираем публикацию прерванной попытки, затем проверяем файл сборки.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> folders.lockUserTree(user));
            session = sessions.findById(id).orElseThrow();
            if (!reconcile(session)) return;
            FileUtils.StreamedFileInfo actual;
            try (InputStream input = new FilterInputStream(Files.newInputStream(storage.data(id))) {
                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    return super.read(bytes, offset, length);
                }
            }) {
                actual = FileUtils.writeAndCalculateSHA256(input, OutputStream.nullOutputStream(), properties.getMaxFileSizeBytes());
            }
            if (actual.size() != session.getSize() || !actual.checksum().equals(session.getChecksum())) {
                finish(session, State.FAILED, "CHECKSUM_MISMATCH");
                cleanup(session);
                return;
            }
            UploadSession ready = session;
            files.registerResumable(ready, user, storage.data(id), (destination, result) -> publish(ready, destination, result));
            cleanup(sessions.findById(id).orElseThrow());
        } catch (FolderNotFoundException | ManagedFolderException | FileConflictException | IllegalArgumentException ex) {
            failIfUnfinished(id, "DESTINATION_UNAVAILABLE");
        } catch (Exception ex) {
            // Включая неизвестный исход COMMIT: не перезаписываем возможный COMPLETED терминальной ошибкой.
            log.warn("Повторим финализацию сессии {}", id, ex);
        } finally { locks.release(id); }
    }

    private void publish(UploadSession session, Path destination, FileItemDto result) {
        try {
            if (destination != null) {
                TransactionTemplate journal = new TransactionTemplate(transactionManager);
                journal.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                journal.executeWithoutResult(status -> {
                    UploadSession current = sessions.findById(session.getId()).orElseThrow();
                    current.setPublicationPath(destination.toString());
                    sessions.saveAndFlush(current);
                });
                session.setPublicationPath(destination.toString());
                storage.publish(session.getId(), destination);
            }
            session.setResultJson(mapper.writeValueAsString(result));
            session.setState(State.COMPLETED);
            session.setFinishedAt(now());
            session.setLastActivityAt(session.getFinishedAt());
            sessions.saveAndFlush(session);
        } catch (FileAlreadyExistsException ex) {
            throw new FileConflictException("Physical name is already occupied");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void failIfUnfinished(UUID id, String reason) {
        UploadSession current = sessions.findById(id).orElseThrow();
        if (!current.terminal()) finish(current, State.FAILED, reason);
        cleanup(current);
    }

    private boolean reconcile(UploadSession session) throws IOException {
        if (storage.reconcile(session)) return true;
        finish(session, State.FAILED, "STORAGE_INCONSISTENT");
        return false;
    }

    private void cleanup(UploadSession session) {
        if (!session.terminal() || session.isCleaned()) return;
        try {
            User user = users.findById(session.getUserId()).orElseThrow();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> folders.lockUserTree(user));
            UploadSession latest = sessions.findById(session.getId()).orElseThrow();
            storage.clean(latest.getId());
            latest.setCleaned(true);
            sessions.saveAndFlush(latest);
        } catch (Exception ex) {
            log.warn("Повторим очистку сессии {}", session.getId(), ex);
        }
    }

    private void finish(UploadSession session, State state, String error) {
        session.setState(state);
        session.setError(error);
        session.setFinishedAt(now());
        sessions.saveAndFlush(session);
    }

    private UploadSession owned(UUID id, User user) {
        return sessions.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new UploadSessionException(HttpStatus.NOT_FOUND, "UPLOAD_SESSION_NOT_FOUND"));
    }

    private void requireUploading(UploadSession session) {
        if (!session.terminal() && session.getLastActivityAt().plus(properties.getInactivityTtl()).isBefore(Instant.now())) {
            finish(session, State.EXPIRED, "UPLOAD_EXPIRED");
        }
        if (session.getState() != State.UPLOADING) throw conflict("UPLOAD_NOT_WRITABLE");
    }

    private void acquire(UUID id) { if (!locks.acquire(id)) throw conflict("UPLOAD_BUSY"); }
    private Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private UploadSessionException conflict(String reason) { return new UploadSessionException(HttpStatus.CONFLICT, reason); }

    private UploadSessionDto dto(UploadSession session) {
        try {
            FileItemDto result = session.getResultJson() == null ? null : mapper.readValue(session.getResultJson(), FileItemDto.class);
            return new UploadSessionDto(session.getId(), session.getClientRequestId(), session.getFolderId(),
                    session.getOriginalFilename(), session.getSize(), session.getChecksum(), session.getBackupSource(),
                    session.getConfirmedOffset(), session.getState(), session.getCreatedAt(), session.getLastActivityAt(),
                    session.getFinishedAt(), properties.getMaxChunkSizeBytes(), result, session.getError());
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid upload result", ex); }
    }
}
