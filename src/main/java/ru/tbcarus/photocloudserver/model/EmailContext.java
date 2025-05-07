package ru.tbcarus.photocloudserver.model;

import lombok.Data;

import java.util.Map;

@Data
public class EmailContext {
    private String from; // глобальная переменная ГП
    private String to; // есть в EA
    private String subject; // есть в EA
    private String attachment; // пока не надо
    private String fromDisplayName; // проверить что это
    private String displayName; // проверить что это
    private String template; // куда-то вынести?
    private Map<String, Object> context; // пока не понятно, куда это включить
}
