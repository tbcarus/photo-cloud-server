package ru.tbcarus.photocloudserver.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tbcarus.photocloudserver.exception.dto.ErrorResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TokenRevokedException.class)
    public ResponseEntity<ErrorResponse> handleTokenRevokedException(TokenRevokedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                        ErrorResponse.builder()
                                .uuid(UUID.randomUUID())
                                .message(e.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(EntityAlreadyExistException.class)
    public ResponseEntity<ErrorResponse> handleEntityAlreadyExist(EntityAlreadyExistException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ErrorResponse.builder()
                                .uuid(UUID.randomUUID())
                                .message(e.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ErrorResponse.builder()
                                .uuid(UUID.randomUUID())
                                .message(e.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(TickerRequestException.class)
    public ResponseEntity<ErrorResponse> handleTickerRequestException(TickerRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        ErrorResponse.builder()
                                .uuid(UUID.randomUUID())
                                .message(e.getMessage())
                                .build()
                );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> {
            String fieldName = error.getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errors);
    }
}
