package ru.tbcarus.photocloudserver.exception;

public class FileItemNotFoundException extends RuntimeException {
    public FileItemNotFoundException(Long fileItemId) {
        super(String.format("File item %d not found", fileItemId));
    }
}
