package ru.tbcarus.photocloudserver.exception;

public class ThumbnailBusyException extends RuntimeException {
    public ThumbnailBusyException() {
        super("All thumbnail generation slots are busy");
    }
}
