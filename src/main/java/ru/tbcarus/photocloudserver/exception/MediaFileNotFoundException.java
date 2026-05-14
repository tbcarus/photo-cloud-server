package ru.tbcarus.photocloudserver.exception;

public class MediaFileNotFoundException extends RuntimeException {
    public MediaFileNotFoundException(Long mediaFileId) {
        super(String.format("Media file %d not found", mediaFileId));
    }
}
