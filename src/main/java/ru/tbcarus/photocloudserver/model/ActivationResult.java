package ru.tbcarus.photocloudserver.model;

// Итог перехода по ссылке подтверждения регистрации; причины отказа нужны только для текста страницы.
public enum ActivationResult {
    ACTIVATED,
    ALREADY_ACTIVE,
    INVALIDATED,
    EXPIRED,
    INVALID_LINK
}
