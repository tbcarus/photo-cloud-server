package ru.tbcarus.photocloudserver.exception;

import lombok.Getter;
import ru.tbcarus.photocloudserver.model.EmailRequestType;

// Письмо не сформировано или не принято SMTP; причина сохраняется только для серверной диагностики.
@Getter
public class EmailSendException extends RuntimeException {
    private final EmailRequestType type;

    public EmailSendException(EmailRequestType type, Throwable cause) {
        super(type + " email was not sent", cause);
        this.type = type;
    }
}
