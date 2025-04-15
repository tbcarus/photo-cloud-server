package ru.tbcarus.photocloudserver.model;

public enum EmailRequestType {
    ACTIVATE ("Spending. Активация нового пользователя"),
    RESET_PASSWORD ("Spending. Восстановление пароля");

    EmailRequestType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    private String title;
}
