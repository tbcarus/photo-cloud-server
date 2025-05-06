package ru.tbcarus.photocloudserver.util;

public class ConfigUtil {

    public static final int DEFAULT_EXPIRED_DAYS = 3;
    public static final int ACTIVE_REQUESTS_MAX = 3;

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
