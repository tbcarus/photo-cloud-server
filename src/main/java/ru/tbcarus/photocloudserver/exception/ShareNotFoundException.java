package ru.tbcarus.photocloudserver.exception;

public class ShareNotFoundException extends RuntimeException {
    public ShareNotFoundException(Long shareId) {
        super("Share " + shareId + " not found");
    }
}
