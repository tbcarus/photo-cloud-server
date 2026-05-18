package ru.tbcarus.photocloudserver.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.tbcarus.photocloudserver.exception.dto.ErrorCode;
import ru.tbcarus.photocloudserver.exception.dto.ErrorResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private ErrorResponse error(ErrorCode code, String message) {
        return ErrorResponse.builder()
                .id(UUID.randomUUID())
                .code(code)
                .message(message)
                .build();
    }

    private ErrorResponse validationError(Map<String, String> fieldErrors) {
        return ErrorResponse.builder()
                .id(UUID.randomUUID())
                .code(ErrorCode.VALIDATION_ERROR)
                .message("Validation failed")
                .fieldErrors(fieldErrors)
                .build();
    }

    @ExceptionHandler(BadRegistrationRequest.class)
    public ResponseEntity<ErrorResponse> handleBadRegistrationRequest(BadRegistrationRequest e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REGISTRATION_REQUEST, e.getErrorType().getTitle()));
    }

    @ExceptionHandler(TokenRevokedException.class)
    public ResponseEntity<ErrorResponse> handleTokenRevokedException(TokenRevokedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(ErrorCode.REFRESH_TOKEN_REVOKED, e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ErrorCode.CONFLICT, "Email is already registered"));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(ErrorCode.INVALID_REFRESH_TOKEN, "Invalid refresh token"));
    }

    @ExceptionHandler(RefreshTokenOwnershipException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenOwnership(RefreshTokenOwnershipException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(ErrorCode.REFRESH_TOKEN_OWNERSHIP_ERROR, "Refresh token does not belong to current user"));
    }

    @ExceptionHandler(RefreshTokenNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenNotFound(RefreshTokenNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.REFRESH_TOKEN_NOT_FOUND, "Refresh token not found"));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(MediaFileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMediaFileNotFound(MediaFileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.MEDIA_FILE_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleFileNotFound(FileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(ErrorCode.DATABASE_CONSTRAINT_VIOLATION, "Database constraint violation"));
    }

    @ExceptionHandler(TickerRequestException.class)
    public ResponseEntity<ErrorResponse> handleTickerRequestException(TickerRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, "Malformed or missing request body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, "Required request parameter is missing"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(validationError(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException e) {
        Map<String, String> errors = new HashMap<>();
        e.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            String fieldName = path.substring(path.lastIndexOf('.') + 1);
            errors.put(fieldName, violation.getMessage());
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(validationError(errors));
    }
}
