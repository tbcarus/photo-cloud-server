package ru.tbcarus.photocloudserver.model;

public enum EmailRequestType {
    ACTIVATE ("Photo-cloud. Активация нового пользователя"),
    PASSWORD_RESET ("Photo-cloud. Восстановление пароля");

    EmailRequestType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    private String title;
}
