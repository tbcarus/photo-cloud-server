package ru.tbcarus.photocloudserver.service.upload;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Один процесс Server: занятые UUID не образуют очередь медленных сетевых запросов.
@Component
public class UploadSessionLocks {
    private final Map<UUID, Boolean> busy = new HashMap<>();

    public synchronized boolean acquire(UUID id) { return busy.putIfAbsent(id, Boolean.TRUE) == null; }
    public synchronized void release(UUID id) { busy.remove(id); }
}
