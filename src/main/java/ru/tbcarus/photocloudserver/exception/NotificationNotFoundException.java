package ru.tbcarus.photocloudserver.exception;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(Long notificationId) {
        super("Notification " + notificationId + " not found");
    }
}
