package ru.tbcarus.photocloudserver.exception;

public enum ErrorType {
    PERIOD_EXPIRED("Вышел срок действия запроса. Пожалуйста, сделайте новый запрос."),
    NOT_FOUND("Запись не найдена"),
    DO_NOT_MATCH("Не совпадают"),
    WRONG_LENGTH("Неверная длина"),
    TOO_MUCH_REPEAT_REQUESTS("Повторный запрос возможен через n дней");

    private final String title;

    ErrorType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
