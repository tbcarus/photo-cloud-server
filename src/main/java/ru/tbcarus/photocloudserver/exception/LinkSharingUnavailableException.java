package ru.tbcarus.photocloudserver.exception;

// LINK-ссылки отключены: не заданы ключ шифрования или публичный адрес Server.
public class LinkSharingUnavailableException extends RuntimeException {
    public LinkSharingUnavailableException() {
        super("Public links are not configured on this server");
    }
}
