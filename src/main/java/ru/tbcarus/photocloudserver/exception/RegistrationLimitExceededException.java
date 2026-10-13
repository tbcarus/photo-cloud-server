package ru.tbcarus.photocloudserver.exception;

import lombok.Getter;

@Getter
public class RegistrationLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RegistrationLimitExceededException(long retryAfterSeconds) {
        super("Registration attempts limit reached. Try again in " + formatDuration(retryAfterSeconds)
                + ". If you cannot restore access yourself, contact my-cld@yandex.ru");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    // Округление вверх до минут: текст не должен обещать повтор раньше Retry-After.
    private static String formatDuration(long seconds) {
        long totalMinutes = (seconds + 59) / 60;
        long days = totalMinutes / (24 * 60);
        long hours = totalMinutes / 60 % 24;
        long minutes = totalMinutes % 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(" d ");
        }
        if (hours > 0) {
            text.append(hours).append(" h ");
        }
        if (minutes > 0 || text.isEmpty()) {
            text.append(minutes).append(" min");
        }
        return text.toString().trim();
    }
}
