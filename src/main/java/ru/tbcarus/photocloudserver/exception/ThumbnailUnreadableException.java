package ru.tbcarus.photocloudserver.exception;

public class ThumbnailUnreadableException extends RuntimeException {
    public ThumbnailUnreadableException(String message) {
        super(message);
    }

    public ThumbnailUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
