package ru.tbcarus.photocloudserver.model.dto;

import lombok.Getter;

import java.time.Instant;

// Отсутствующее поле и явный null различаются: Jackson вызывает сеттер и для null, а для отсутствующего поля — нет.
@Getter
public class UpdateShareRequest {
    private Instant expiresAt;
    private boolean expiresAtPresent;

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        this.expiresAtPresent = true;
    }
}
