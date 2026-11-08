package ru.tbcarus.photocloudserver.exception;

import lombok.Getter;

import java.util.Map;

// Недопустимые получатели групповой выдачи: email → причина. Отсутствующий, неактивный и заблокированный
// аккаунт описываются одной причиной, чтобы ответ не раскрывал состояние чужой учётной записи.
@Getter
public class ShareRecipientException extends RuntimeException {
    private final Map<String, String> rejected;

    public ShareRecipientException(Map<String, String> rejected) {
        super("Some recipients cannot receive access");
        this.rejected = rejected;
    }
}
