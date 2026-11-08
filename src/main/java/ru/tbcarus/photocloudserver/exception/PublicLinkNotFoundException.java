package ru.tbcarus.photocloudserver.exception;

// Неизвестный, отозванный, истёкший токен и объект вне ссылки неразличимы: текст не раскрывает причину и не содержит токен.
public class PublicLinkNotFoundException extends RuntimeException {
    public PublicLinkNotFoundException() {
        super("Not found");
    }
}
