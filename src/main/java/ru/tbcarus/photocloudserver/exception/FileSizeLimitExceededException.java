package ru.tbcarus.photocloudserver.exception;

public class FileSizeLimitExceededException extends RuntimeException {

    public FileSizeLimitExceededException(long maxSizeBytes) {
        super("File size exceeds limit: " + maxSizeBytes + " bytes");
    }
}
