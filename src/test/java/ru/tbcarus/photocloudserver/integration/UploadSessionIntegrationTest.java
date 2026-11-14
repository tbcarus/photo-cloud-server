package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.exception.UploadSessionException;
import ru.tbcarus.photocloudserver.model.*;
import ru.tbcarus.photocloudserver.model.dto.*;
import ru.tbcarus.photocloudserver.repository.UploadSessionRepository;
import ru.tbcarus.photocloudserver.service.FolderService;
import ru.tbcarus.photocloudserver.service.upload.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadSessionIntegrationTest extends AbstractIntegrationTest {
    @Autowired UploadSessionService service;
    @Autowired UploadSessionRepository sessions;
    @Autowired FolderService folders;
    @MockitoSpyBean UploadStorage storage;
    @MockitoSpyBean UploadSessionRepository sessionSpy;
    @MockitoSpyBean org.springframework.transaction.PlatformTransactionManager transactionManager;
    private static final String URL = "/api/v1/upload-sessions";
    private static final byte[] BYTES = new byte[2048];
    static { new Random(42).nextBytes(BYTES); }

    private User user() { return createUser(UUID.randomUUID() + "@test.local", "pass1"); }
    private CreateUploadSessionRequest request(User user, String name, byte[] bytes) {
        return new CreateUploadSessionRequest(UUID.randomUUID(), folders.getOrCreateRoot(user).getId(), name,
                bytes.length, sha256(bytes), null);
    }
    private UploadSessionDto create(User user) { return service.create(request(user, "large.bin", BYTES), user); }
    private void send(User user, UUID id, int start, int end) throws IOException {
        service.patch(id, user, start, end - start, new ByteArrayInputStream(BYTES, start, end - start));
    }
    private UploadSessionDto finish(User user, UploadSessionDto session) throws IOException {
        send(user, session.id(), 0, BYTES.length);
        service.complete(session.id(), user);
        service.finalizeSession(session.id());
        return service.get(session.id(), user);
    }

    @Test
    void creationIsIdempotentAndParametersAndOwnerAreIsolated() throws Exception {
        User owner = user();
        CreateUploadSessionRequest request = request(owner, "file.bin", BYTES);
        UploadSessionDto first = service.create(request, owner);
        assertThat(service.create(request, owner)).isEqualTo(first);
        assertThatThrownBy(() -> service.create(new CreateUploadSessionRequest(request.clientRequestId(), request.folderId(),
                "other.bin", request.size(), request.checksum(), null), owner)).hasMessage("REQUEST_MISMATCH");
        User other = user();
        assertThatThrownBy(() -> service.get(first.id(), other)).hasMessage("UPLOAD_SESSION_NOT_FOUND");
        assertThatThrownBy(() -> service.cancel(first.id(), other)).hasMessage("UPLOAD_SESSION_NOT_FOUND");
        assertThatThrownBy(() -> service.complete(first.id(), other)).hasMessage("UPLOAD_SESSION_NOT_FOUND");
        assertThatThrownBy(() -> service.patch(first.id(), other, 0, 1, new ByteArrayInputStream(BYTES)))
                .hasMessage("UPLOAD_SESSION_NOT_FOUND");
        assertThatThrownBy(() -> service.create(request, other)).isInstanceOf(ru.tbcarus.photocloudserver.exception.FolderNotFoundException.class);
    }

    @Test
    void concurrentCreationUsesOneRequestIdentity() throws Exception {
        User owner = user();
        CreateUploadSessionRequest request = request(owner, "concurrent.bin", BYTES);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<UUID>> calls = Collections.nCopies(4, () -> service.create(request, owner).id());
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> result : executor.invokeAll(calls)) ids.add(result.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(sessions.count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void acceptedCancellationPreventsQueuedFinalization() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        assertThat(service.cancel(session.id(), owner).state()).isEqualTo(UploadSession.State.CANCELLED);
        service.finalizeSession(session.id());
        assertThat(service.complete(session.id(), owner).state()).isEqualTo(UploadSession.State.CANCELLED);
        assertThat(fileItemRepository.count()).isZero();
        assertThat(Files.exists(storage.data(session.id()))).isFalse();
    }

    @Test
    void declared20GiBLimitAndGlobalReservationUseLongValuesWithoutAllocatingFiles() {
        User owner = user();
        Long folder = folders.getOrCreateRoot(owner).getId();
        long maximum = 20L * 1024 * 1024 * 1024;
        CreateUploadSessionRequest firstRequest = new CreateUploadSessionRequest(UUID.randomUUID(), folder, "first.bin", maximum, "a".repeat(64), null);
        UploadSessionDto first = service.create(firstRequest, owner);
        service.create(new CreateUploadSessionRequest(UUID.randomUUID(), folder, "second.bin", maximum, "a".repeat(64), null), owner);
        assertThat(sessions.reservedBytes()).isEqualTo(2 * maximum);
        assertThatThrownBy(() -> service.create(new CreateUploadSessionRequest(UUID.randomUUID(), folder, "over.bin", maximum + 1, "a".repeat(64), null), owner))
                .isInstanceOf(ru.tbcarus.photocloudserver.exception.FileSizeLimitExceededException.class);
        assertThatThrownBy(() -> service.create(request(owner, "third.bin", BYTES), owner)).hasMessage("UPLOAD_CAPACITY_EXCEEDED");
        service.cancel(first.id(), owner);
        assertThat(sessions.reservedBytes()).isEqualTo(maximum);
        assertThat(service.create(request(owner, "third.bin", BYTES), owner).state()).isEqualTo(UploadSession.State.UPLOADING);
    }

    @Test
    void apiUsesJwtValidationRawChunksAndStableCompletionResult() throws Exception {
        User owner = user();
        String token = loginAndGetAccessToken(owner.getEmail(), "pass1");
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("Authorization", authHeader(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        String response = mockMvc.perform(post(URL).header("Authorization", authHeader(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(request(owner, "large.bin", BYTES))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = objectMapper.readValue(response, UploadSessionDto.class).id();
        mockMvc.perform(patch(URL + "/" + id).header("Authorization", authHeader(token)).header("Upload-Offset", 0)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(BYTES))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedOffset").value(BYTES.length));
        mockMvc.perform(post(URL + "/" + id + "/complete").header("Authorization", authHeader(token)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("FINALIZING"));
        mockMvc.perform(post(URL + "/" + id + "/complete").header("Authorization", authHeader(token)))
                .andExpect(status().isAccepted());
        service.finalizeSession(id);
        mockMvc.perform(get(URL + "/" + id).header("Authorization", authHeader(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.result.checksum").value(sha256(BYTES)));
        mockMvc.perform(post(URL + "/" + id + "/complete").header("Authorization", authHeader(token)))
                .andExpect(status().isOk());
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", authHeader(token)))
                .andExpect(status().isConflict());
        assertThat(fileItemRepository.count()).isEqualTo(1);
    }

    @Test
    void severalChunksLostResponseAndRepeatedBlockUseConfirmedOffset() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, 700);
        assertThat(service.get(session.id(), owner).confirmedOffset()).isEqualTo(700);
        assertThatThrownBy(() -> send(owner, session.id(), 0, 700)).hasMessage("OFFSET_MISMATCH");
        assertThatThrownBy(() -> service.patch(session.id(), owner, -1, 1, new ByteArrayInputStream(BYTES)))
                .hasMessage("OFFSET_MISMATCH");
        assertThatThrownBy(() -> service.complete(session.id(), owner)).hasMessage("UPLOAD_INCOMPLETE");
        send(owner, session.id(), 700, 1500);
        send(owner, session.id(), 1500, BYTES.length);
        assertThat(Files.readAllBytes(storage.data(session.id()))).isEqualTo(BYTES);
        service.complete(session.id(), owner);
        service.finalizeSession(session.id());
        FileItemDto result = service.get(session.id(), owner).result();
        assertThat(Files.readAllBytes(physicalPath(findFileItem(result.getId())))).isEqualTo(BYTES);
        assertThat(service.complete(session.id(), owner).result()).isEqualTo(result);
        assertThat(Files.exists(storage.data(session.id()))).isFalse();
    }

    @Test
    void disconnectedAndShortBlocksNeverAdvanceProgress() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        InputStream broken = new InputStream() {
            int remaining = 100;
            public int read() throws IOException { if (--remaining < 0) throw new IOException("disconnect"); return 1; }
        };
        assertThatThrownBy(() -> service.patch(session.id(), owner, 0, 700, broken)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.patch(session.id(), owner, 0, 700, new ByteArrayInputStream(new byte[100])))
                .isInstanceOf(IOException.class);
        assertThat(service.get(session.id(), owner).confirmedOffset()).isZero();
        assertThat(Files.size(storage.data(session.id()))).isZero();
        assertThat(Files.exists(storage.chunk(session.id()))).isFalse();
        send(owner, session.id(), 0, 700);
    }

    @Test
    void concurrentPatchCancelAndCompleteCannotChangeAnActiveReceiver() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InputStream slow = new ByteArrayInputStream(BYTES) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                reading.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("receiver timeout"); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                return super.read(b, off, len);
            }
        };
        try {
            Future<?> first = executor.submit(() -> { try { service.patch(session.id(), owner, 0, BYTES.length, slow); }
                catch (IOException ex) { throw new UncheckedIOException(ex); } });
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(session.id(), owner).confirmedOffset()).isZero();
            assertThatThrownBy(() -> send(owner, session.id(), 0, 100)).hasMessage("UPLOAD_BUSY");
            assertThatThrownBy(() -> service.cancel(session.id(), owner)).hasMessage("UPLOAD_BUSY");
            assertThatThrownBy(() -> service.complete(session.id(), owner)).hasMessage("UPLOAD_BUSY");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(service.get(session.id(), owner).confirmedOffset()).isEqualTo(BYTES.length);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void appendedButUncommittedTailIsTruncatedAndMissingBytesFailClosed() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, 500);
        Files.write(storage.data(session.id()), new byte[200], StandardOpenOption.APPEND);
        send(owner, session.id(), 500, 1000);
        assertThat(Files.readAllBytes(storage.data(session.id()))).isEqualTo(Arrays.copyOf(BYTES, 1000));
        Files.write(storage.data(session.id()), new byte[10], StandardOpenOption.TRUNCATE_EXISTING);
        assertThatThrownBy(() -> send(owner, session.id(), 1000, 1100)).hasMessage("STORAGE_INCONSISTENT");
        assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.FAILED);
        assertThat(fileItemRepository.count()).isZero();
    }

    @Test
    void databaseFailureAfterAppendDoesNotConfirmTail() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        doThrow(new IllegalStateException("database unavailable")).when(sessionSpy).saveAndFlush(any(UploadSession.class));
        assertThatThrownBy(() -> send(owner, session.id(), 0, 500)).hasMessage("database unavailable");
        reset(sessionSpy);
        assertThat(service.get(session.id(), owner).confirmedOffset()).isZero();
        assertThat(Files.size(storage.data(session.id()))).isEqualTo(500);
        send(owner, session.id(), 0, 700);
        assertThat(Files.readAllBytes(storage.data(session.id()))).isEqualTo(Arrays.copyOf(BYTES, 700));
    }

    @Test
    void checksumMismatchIsTerminalAndNeverPublishes() throws Exception {
        User owner = user();
        CreateUploadSessionRequest request = request(owner, "bad.bin", BYTES);
        UploadSessionDto session = service.create(new CreateUploadSessionRequest(request.clientRequestId(), request.folderId(),
                request.originalFilename(), request.size(), "a".repeat(64), null), owner);
        UploadSessionDto failed = finish(owner, session);
        assertThat(failed.state()).isEqualTo(UploadSession.State.FAILED);
        assertThat(failed.error()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(service.complete(session.id(), owner)).isEqualTo(failed);
        assertThat(fileItemRepository.count()).isZero();
        assertThat(storedObjectRepository.count()).isZero();
        assertThat(Files.exists(storage.data(session.id()))).isFalse();
    }

    @Test
    void recoveryRemovesOnlyOwnUncommittedLinkBeforeFolderRename() throws Exception {
        User owner = user();
        Folder folder = folders.createFolder(owner, null, "before");
        UploadSessionDto session = service.create(new CreateUploadSessionRequest(UUID.randomUUID(), folder.getId(),
                "large.bin", BYTES.length, sha256(BYTES), null), owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        Path destination = libraryStorage.filePath(folder, "large.bin");
        UploadSession row = sessions.findById(session.id()).orElseThrow();
        row.setPublicationPath(destination.toString());
        sessions.saveAndFlush(row);
        Files.createLink(destination, storage.data(session.id()));
        folders.renameFolder(owner, folder.getId(), "after");
        assertThat(Files.exists(destination)).isFalse();
        service.finalizeSession(session.id());
        UploadSessionDto result = service.get(session.id(), owner);
        assertThat(result.state()).isEqualTo(UploadSession.State.COMPLETED);
        assertThat(physicalPath(findFileItem(result.result().getId())).getParent().getFileName().toString()).isEqualTo("after");
        assertThat(fileItemRepository.count()).isEqualTo(1);
    }

    @Test
    void failureAfterPhysicalPublicationRecoversWithoutDuplicate() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IOException("crash after link"); })
                .when(storage).publish(eq(session.id()), any(Path.class));
        service.finalizeSession(session.id());
        assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.FINALIZING);
        assertThat(fileItemRepository.count()).isZero();
        reset(storage);
        service.finalizeSession(session.id());
        assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.COMPLETED);
        assertThat(fileItemRepository.count()).isEqualTo(1);
    }

    @Test
    void publicationRecoveryDoesNotOverwriteConcurrentAcceptedCancellation() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        Path destination = storageRoot().resolve(owner.getId() + "/files/large.bin");
        Files.createDirectories(destination.getParent());
        Files.createLink(destination, storage.data(session.id()));
        UploadSession row = sessions.findById(session.id()).orElseThrow();
        row.setPublicationPath(destination.toString());
        sessions.saveAndFlush(row);
        CountDownLatch recovering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            recovering.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("recovery timeout");
            return null;
        }).when(storage).undoPublication(any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> treeChange = executor.submit(() -> folders.createFolder(owner, session.folderId(), "unrelated"));
            assertThat(recovering.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UploadSessionDto> cancellation = executor.submit(() -> service.cancel(session.id(), owner));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sessions.findById(session.id()).orElseThrow().getState() != UploadSession.State.CANCELLED
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(sessions.findById(session.id()).orElseThrow().getState()).isEqualTo(UploadSession.State.CANCELLED);
            release.countDown();
            treeChange.get(10, TimeUnit.SECONDS);
            assertThat(cancellation.get(10, TimeUnit.SECONDS).state()).isEqualTo(UploadSession.State.CANCELLED);
            assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.CANCELLED);
            assertThat(Files.exists(storage.data(session.id()))).isFalse();
            assertThat(Files.exists(destination)).isFalse();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void unknownCommitOutcomeKeepsCompletedResultAndPublishedBytes() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> { invocation.callRealMethod(); published.set(true); return null; })
                .when(storage).publish(eq(session.id()), any(Path.class));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            org.springframework.transaction.TransactionStatus status = invocation.getArgument(0);
            if (status.isNewTransaction() && published.compareAndSet(true, false)) {
                throw new org.springframework.transaction.TransactionSystemException("commit reply lost");
            }
            return null;
        }).when(transactionManager).commit(any());
        service.finalizeSession(session.id());
        UploadSessionDto completed = service.get(session.id(), owner);
        assertThat(completed.state()).isEqualTo(UploadSession.State.COMPLETED);
        reset(transactionManager, storage);
        service.maintain();
        assertThat(service.complete(session.id(), owner).result().getId()).isEqualTo(completed.result().getId());
        assertThat(Files.readAllBytes(physicalPath(findFileItem(completed.result().getId())))).isEqualTo(BYTES);
        assertThat(fileItemRepository.count()).isEqualTo(1);
    }

    @Test
    void activeFinalizationSerializesCancellationAndDuplicateCompletion() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("publication timeout");
            return invocation.callRealMethod();
        }).when(storage).publish(eq(session.id()), any(Path.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> job = executor.submit(() -> service.finalizeSession(session.id()));
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.complete(session.id(), owner).state()).isEqualTo(UploadSession.State.FINALIZING);
            assertThatThrownBy(() -> service.cancel(session.id(), owner)).hasMessage("UPLOAD_BUSY");
            service.finalizeSession(session.id());
            release.countDown();
            job.get(10, TimeUnit.SECONDS);
            assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.COMPLETED);
            assertThat(fileItemRepository.count()).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void foreignPhysicalFileIsNeverAdoptedOrRemoved() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        Path destination = storageRoot().resolve(owner.getId() + "/files/large.bin");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "foreign bytes");
        assertThat(finish(owner, session).state()).isEqualTo(UploadSession.State.FAILED);
        assertThat(Files.readString(destination)).isEqualTo("foreign bytes");
        assertThat(fileItemRepository.count()).isZero();
    }

    @Test
    void cancellationAndFailedPhysicalCleanupAreRetriedAndResultsExpire() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, 500);
        doThrow(new IOException("busy file")).when(storage).clean(session.id());
        assertThat(service.cancel(session.id(), owner).state()).isEqualTo(UploadSession.State.CANCELLED);
        assertThat(sessions.findById(session.id()).orElseThrow().isCleaned()).isFalse();
        assertThatThrownBy(() -> send(owner, session.id(), 500, 700)).hasMessage("UPLOAD_NOT_WRITABLE");
        reset(storage);
        assertThat(service.cancel(session.id(), owner).state()).isEqualTo(UploadSession.State.CANCELLED);
        assertThat(Files.exists(storage.data(session.id()))).isFalse();
        UploadSession row = sessions.findById(session.id()).orElseThrow();
        row.setFinishedAt(Instant.now().minus(31, ChronoUnit.DAYS));
        sessions.saveAndFlush(row);
        service.maintain();
        assertThat(sessions.existsById(session.id())).isFalse();
    }

    @Test
    void statusReadsDoNotExtendInactivityTtl() throws Exception {
        User owner = user();
        UploadSessionDto session = create(owner);
        send(owner, session.id(), 0, 500);
        UploadSession row = sessions.findById(session.id()).orElseThrow();
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        row.setLastActivityAt(old);
        sessions.saveAndFlush(row);
        assertThat(service.get(session.id(), owner).lastActivityAt()).isEqualTo(old.truncatedTo(ChronoUnit.MICROS));
        service.maintain();
        assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.EXPIRED);
        assertThat(Files.exists(storage.data(session.id()))).isFalse();
    }

    @Test
    void destinationPermissionsAreCheckedAgainBeforePublication() throws Exception {
        User owner = user();
        UUID source = UUID.randomUUID();
        Folder folder = folders.createBackupSource(owner, "Documents", source);
        UploadSessionDto session = service.create(new CreateUploadSessionRequest(UUID.randomUUID(), folder.getId(),
                "large.bin", BYTES.length, sha256(BYTES), source), owner);
        send(owner, session.id(), 0, BYTES.length);
        service.complete(session.id(), owner);
        folders.disableBackupSource(owner, folder.getId());
        service.finalizeSession(session.id());
        assertThat(service.get(session.id(), owner).state()).isEqualTo(UploadSession.State.FAILED);
        assertThat(fileItemRepository.count()).isZero();
    }

    @Test
    void libraryAndCameraKeepTheirDeduplicationRules() throws Exception {
        User owner = user();
        Long root = folders.getOrCreateRoot(owner).getId();
        UploadSessionDto first = finish(owner, create(owner));
        assertThat(finish(owner, create(owner)).result().getId()).isEqualTo(first.result().getId());
        UploadSessionDto otherName = service.create(new CreateUploadSessionRequest(UUID.randomUUID(), root,
                "other.bin", BYTES.length, sha256(BYTES), null), owner);
        assertThat(finish(owner, otherName).result().getId()).isNotEqualTo(first.result().getId());
        Long camera = folders.getOrCreateCamera(owner).getId();
        UploadSessionDto cameraFirst = service.create(new CreateUploadSessionRequest(UUID.randomUUID(), camera,
                "one.bin", BYTES.length, sha256(BYTES), null), owner);
        Long cameraFileId = finish(owner, cameraFirst).result().getId();
        UploadSessionDto cameraSecond = service.create(new CreateUploadSessionRequest(UUID.randomUUID(), camera,
                "two.bin", BYTES.length, sha256(BYTES), null), owner);
        assertThat(finish(owner, cameraSecond).result().getId()).isEqualTo(cameraFileId);
        assertThat(fileItemRepository.count()).isEqualTo(3);
        assertThat(storedObjectRepository.count()).isEqualTo(3);
    }
}
