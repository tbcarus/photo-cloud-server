package ru.tbcarus.photocloudserver.model.dto;

import java.util.List;

public record ChecksumExistsResponse(
        List<String> existing,
        List<String> missing
) {
}
