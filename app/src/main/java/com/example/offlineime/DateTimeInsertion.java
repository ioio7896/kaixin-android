package com.example.offlineime;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats the device's current local time when the user selects an item. */
final class DateTimeInsertion {
    static final String[] FORMATS = {"date", "cn_date", "time", "date_time", "weekday"};

    private DateTimeInsertion() { }

    static String label(String id) {
        switch (id) {
            case "date": return "日期";
            case "cn_date": return "中文日期";
            case "time": return "时间";
            case "date_time": return "日期和时间";
            case "weekday": return "日期、星期和时间";
            default: return "";
        }
    }

    static String format(String id) {
        String pattern;
        switch (id) {
            case "date": pattern = "yyyy-MM-dd"; break;
            case "cn_date": pattern = "yyyy年M月d日"; break;
            case "time": pattern = "HH:mm"; break;
            case "date_time": pattern = "yyyy-MM-dd HH:mm"; break;
            case "weekday": pattern = "yyyy年M月d日 EEEE HH:mm"; break;
            default: return null;
        }
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA));
    }
}
