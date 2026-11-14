package ru.tbcarus.photocloudserver.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class UploadSessionException extends RuntimeException {
    private final HttpStatus status;
    private final String reason;

    public UploadSessionException(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }
}
