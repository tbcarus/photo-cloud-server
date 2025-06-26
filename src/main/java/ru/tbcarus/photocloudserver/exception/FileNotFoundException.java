package ru.tbcarus.photocloudserver.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class FileNotFoundException extends RuntimeException {
    private final String FileName;
    private final String message;
}
