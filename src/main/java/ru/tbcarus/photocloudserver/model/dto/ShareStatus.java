package ru.tbcarus.photocloudserver.model.dto;

// Вычисляется по expiresAt в момент ответа; в БД не хранится.
public enum ShareStatus {
    ACTIVE,
    EXPIRED
}
