package ru.tbcarus.photocloudserver.util;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Утильный класс для работы с датами
public class DateUtil {
    public static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy-MM-dd"); // Форматер
    // Форматтер для конвертирования LocalDate в TimeStamp
    public static final DateTimeFormatter DTFORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss");
    // Форматтер для вывода времени и даты по-русски, а не через жопу.
    public static final DateTimeFormatter DTFORMATTER_RU = DateTimeFormatter.ofPattern("hh:mm:ss dd-MM-yyyy");
    // Форматтер для преобразования дат для парсинга в LocalDate
    public static final DateTimeFormatter DTFORMATTER_DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    // Форматтер для вывода даты по-русски, а не через жопу.
    public static final DateTimeFormatter DTFORMATTER_DATE_ONLY_RU = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    // Условная начальная дата, раньше которой считается, что трат нет. Используется для запроса всех трат в БД
    public static final LocalDateTime ALL_TIME_START = LocalDateTime.of(1900, 1, 1, 0, 0, 0);
    // Не пойму, почему я так сделал текущую дату. Наверное, это повлечёт за собой ошибку
//    public static final LocalDateTime NOW = LocalDateTime.now();

    // Возвращает дату начала периода. Сравнивает текущую дату с установленным днём начала периода
    // и при необходимости откручивает один месяц назад.
    public static LocalDate getStartPeriod(int day) {
        LocalDateTime NOW = getLocalDateTimeNow();
        if (day < NOW.getDayOfMonth()) {
            return LocalDate.of(NOW.getYear(), NOW.getMonthValue(), day);
        } else {
            return LocalDate.of(NOW.getYear(), NOW.getMonthValue(), day).minusMonths(1);
        }
    }

    public static LocalDate setDay(int day, LocalDate date) {
        date.withDayOfMonth(day);
        if (day <= date.getDayOfMonth()) {
            return date;
        } else {
            return date.minusMonths(1);
        }
    }

    public static LocalDate getLocalDateNow() {
        return LocalDate.now();
    }
    public static LocalDateTime getLocalDateTimeNow() { return LocalDateTime.now(); }
}
