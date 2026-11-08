package ru.tbcarus.photocloudserver.model;

// Хранится в БД строковым именем: переименование значения требует миграции данных.
public enum NotificationType {
    SHARE_GRANTED,
    // Зарезервированы: бизнес-логики создания пока нет.
    BACKUP_FAILED,
    SYSTEM_MESSAGE
}
