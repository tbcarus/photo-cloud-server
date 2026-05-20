package ru.tbcarus.photocloudserver.service.metadata;

public interface FileMetadataExtractor {
    ExtractedFileMetadata extract(byte[] fileBytes, String mimeType);
}
