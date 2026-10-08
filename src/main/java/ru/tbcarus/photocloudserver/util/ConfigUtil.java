package ru.tbcarus.photocloudserver.util;

import org.springframework.beans.factory.annotation.Value;

public class ConfigUtil {

    @Value("${auth.request.valid-days}")
    public static int DEFAULT_EXPIRED_DAYS;
    @Value("${auth.request.active-max}")
    public static int ACTIVE_REQUESTS_MAX;

    public static final String SELF_COLOR = "#ffc107";

    public static String getStringDefaultDays() {
        switch (DEFAULT_EXPIRED_DAYS) {
            case 1:
                return DEFAULT_EXPIRED_DAYS + " день";
            case 2:
                return DEFAULT_EXPIRED_DAYS + " дня";
            case 3:
                return DEFAULT_EXPIRED_DAYS + " дня";
            case 4:
                return DEFAULT_EXPIRED_DAYS + " дня";
            default:
                return DEFAULT_EXPIRED_DAYS + " дней";
        }
    }
}
