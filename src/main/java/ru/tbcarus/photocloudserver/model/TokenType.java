package ru.tbcarus.photocloudserver.model;

import lombok.Getter;

@Getter
public enum TokenType {
    TOKEN_TYPE("token_type"),
    ACCESS("ACCESS"),
    REFRESH("REFRESH");

    private final String value;

    TokenType(String value) {
        this.value = value;
    }
}
