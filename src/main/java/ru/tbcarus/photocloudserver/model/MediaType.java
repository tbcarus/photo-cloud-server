package ru.tbcarus.photocloudserver.model;

import java.util.Arrays;

public enum MediaType {
    IMAGE("image"),
    VIDEO("video"),
    FILE("application");

    private final String mimePrefix;

    MediaType(String mimePrefix) {
        this.mimePrefix = mimePrefix;
    }

    public String getMimePrefix() {
        return this.mimePrefix;
    }

    public static MediaType fromMimeType(String mimeType) {
        return Arrays.stream(values())
                .filter(type -> mimeType != null && mimeType.startsWith(type.mimePrefix))
                .findFirst()
                .orElse(FILE);
    }
}
