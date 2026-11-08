package ru.tbcarus.photocloudserver.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.tbcarus.photocloudserver.exception.dto.ErrorCode;
import ru.tbcarus.photocloudserver.exception.dto.ErrorResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
                .body(error(ErrorCode.CONFLICT, "Account with this email is already activated. Please log in"));
    }

    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<ErrorResponse> handleEmailSend(EmailSendException e) {
        String message = switch (e.getType()) {
            case ACTIVATE -> "Registration data was saved, but the verification email could not be sent. "
                    + "Please try again later or contact my-cld@yandex.ru.";
            case PASSWORD_RESET -> "The password reset email could not be sent. Please try again later.";
        };
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(ErrorCode.EMAIL_NOT_SENT, message));
    }

    @ExceptionHandler(RegistrationLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRegistrationLimitExceeded(RegistrationLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(error(ErrorCode.TOO_MANY_REQUESTS, e.getMessage()));
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

    // Ошибки бинарных download/thumbnail: тип JSON задан явно, иначе при Accept: image/* тело не записывается
    // и ответ уходит в /error, где без аутентификации становится 401.
    @ExceptionHandler(FileItemNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleFileItemNotFound(FileItemNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.FILE_ITEM_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleFileNotFound(FileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(FileSizeLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleFileSizeLimitExceeded(FileSizeLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error(ErrorCode.FILE_TOO_LARGE, e.getMessage()));
    }

    @ExceptionHandler(ThumbnailUnsupportedException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailUnsupported(ThumbnailUnsupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getMessage()));
    }

    @ExceptionHandler(ThumbnailUnreadableException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailUnreadable(ThumbnailUnreadableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.IMAGE_UNREADABLE, e.getMessage()));
    }

    @ExceptionHandler(ThumbnailTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailTooLarge(ThumbnailTooLargeException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.IMAGE_TOO_LARGE, e.getMessage()));
    }

    // Очереди нет: клиент повторяет запрос позже.
    @ExceptionHandler(ThumbnailBusyException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailBusy(ThumbnailBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(error(ErrorCode.SERVICE_BUSY, e.getMessage()));
    }

    @ExceptionHandler(FileAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleFileAccessDenied(FileAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error(ErrorCode.FORBIDDEN, e.getMessage()));
    }

    @ExceptionHandler(FileConflictException.class)
    public ResponseEntity<ErrorResponse> handleFileConflict(FileConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ErrorCode.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(FolderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleFolderNotFound(FolderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(FolderConflictException.class)
    public ResponseEntity<ErrorResponse> handleFolderConflict(FolderConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ErrorCode.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(ManagedFolderException.class)
    public ResponseEntity<ErrorResponse> handleManagedFolder(ManagedFolderException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(ErrorCode.FOLDER_MANAGED, e.getMessage()));
    }

    // Публичные download и миниатюра: тип JSON задан явно, как у FileItemNotFoundException.
    @ExceptionHandler(PublicLinkNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePublicLinkNotFound(PublicLinkNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(LinkSharingUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleLinkSharingUnavailable(LinkSharingUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(ErrorCode.LINK_SHARING_UNAVAILABLE, e.getMessage()));
    }

    @ExceptionHandler(ShareNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleShareNotFound(ShareNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(ErrorCode.NOT_FOUND, e.getMessage()));
    }

    // Ни один доступ не создан: клиент видит все отклонённые адреса сразу.
    @ExceptionHandler(ShareRecipientException.class)
    public ResponseEntity<ErrorResponse> handleShareRecipient(ShareRecipientException e) {
        ErrorResponse body = error(ErrorCode.SHARE_RECIPIENT_UNAVAILABLE, e.getMessage());
        body.setFieldErrors(e.getRejected());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(FolderOperationException.class)
    public ResponseEntity<ErrorResponse> handleFolderOperation(FolderOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error(ErrorCode.FILE_TOO_LARGE, "Multipart request exceeds configured limit"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    // Ошибки физического хранилища (в т.ч. занятый путь) — 5xx, а не 409: Android считает 409 постоянной ошибкой.
    // Обработка здесь не даёт исключению уйти в /error, где без аутентификации ответ стал бы 401.
    // Сообщение общее: текст IOException содержит физические пути.
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIoException(IOException e) {
        log.error("Ошибка операции с физическим хранилищем", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(ErrorCode.INTERNAL_ERROR, "Physical storage operation failed"));
    }

    // Файловая операция внутри транзакции передаёт IOException в обёртке: иначе исключение ушло бы в /error и стало 401.
    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<ErrorResponse> handleUncheckedIoException(UncheckedIOException e) {
        return handleIoException(e.getCause());
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

    // Например, backupSource multipart-запроса не является UUID.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(ErrorCode.BAD_REQUEST, "Invalid value of request parameter " + e.getName()));
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
