package ru.tbcarus.photocloudserver.model;

import java.util.Set;

public enum FileType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    OTHER;

    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "application/rtf"
    );

    private static final Set<String> ARCHIVE_MIME_TYPES = Set.of(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/gzip",
            "application/x-tar"
    );

    public static FileType fromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return OTHER;
        }
        String normalized = mimeType.toLowerCase();
        if (normalized.startsWith("image/")) {
            return IMAGE;
        }
        if (normalized.startsWith("video/")) {
            return VIDEO;
        }
        if (normalized.startsWith("audio/")) {
            return AUDIO;
        }
        if (DOCUMENT_MIME_TYPES.contains(normalized)) {
            return DOCUMENT;
        }
        if (ARCHIVE_MIME_TYPES.contains(normalized)) {
            return ARCHIVE;
        }
        return OTHER;
    }
}
