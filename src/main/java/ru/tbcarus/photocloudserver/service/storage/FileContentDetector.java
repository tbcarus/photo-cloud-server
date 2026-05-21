package ru.tbcarus.photocloudserver.service.storage;

import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class FileContentDetector {

    private final Tika tika = new Tika();

    public String detectMimeType(Path file) throws IOException {
        return tika.detect(file);
    }
}
