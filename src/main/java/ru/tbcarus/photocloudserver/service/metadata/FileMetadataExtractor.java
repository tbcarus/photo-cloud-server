package ru.tbcarus.photocloudserver.service.metadata;

import java.nio.file.Path;

public interface FileMetadataExtractor {
    ExtractedFileMetadata extract(Path file, String mimeType);
}
