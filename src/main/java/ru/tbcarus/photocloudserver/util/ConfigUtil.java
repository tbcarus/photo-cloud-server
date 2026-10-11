package ru.tbcarus.photocloudserver.util;

public class ConfigUtil {

    public static final String SELF_COLOR = "#ffc107";

    public static String getStringDefaultDays(int days) {
        switch (days) {
            case 1:
                return days + " день";
            case 2:
                return days + " дня";
            case 3:
                return days + " дня";
            case 4:
                return days + " дня";
            default:
                return days + " дней";
        }
    }
}
