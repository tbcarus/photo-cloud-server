package ru.tbcarus.photocloudserver.exception;

public class FileAccessDeniedException extends RuntimeException {

    public FileAccessDeniedException(String message) {
        super(message);
    }
}
