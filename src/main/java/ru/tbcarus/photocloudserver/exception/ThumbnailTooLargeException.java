package ru.tbcarus.photocloudserver.exception;

public class ThumbnailTooLargeException extends RuntimeException {
    public ThumbnailTooLargeException(String message) {
        super(message);
    }
}
