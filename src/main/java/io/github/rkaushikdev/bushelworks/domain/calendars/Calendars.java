package io.github.rkaushikdev.bushelworks.domain.calendars;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Month arithmetic the office keeps, and the house calendar for a crop year. */
public final class Calendars {

    private Calendars() {}

    /** The last calendar day of the month {@code day} falls in. */
    public static LocalDate monthEnd(LocalDate day) {
        return day.with(TemporalAdjusters.lastDayOfMonth());
    }

    public static LocalDate monthStart(LocalDate day) {
        return day.withDayOfMonth(1);
    }

    /** Whole calendar months from {@code start} to {@code end}, never negative. */
    public static int monthsBetween(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new CalendarError(end + " is before " + start);
        }
        int months = (end.getYear() - start.getYear()) * 12 + (end.getMonthValue() - start.getMonthValue());
        if (end.getDayOfMonth() < start.getDayOfMonth()) {
            months -= 1;
        }
        return Math.max(months, 0);
    }

    /** Every month end in the span, which is what the storage run walks. */
    public static List<LocalDate> eachMonthEnd(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new CalendarError(end + " is before " + start);
        }
        List<LocalDate> ends = new ArrayList<>();
        for (LocalDate cursor = monthEnd(start); !cursor.isAfter(end); cursor = monthEnd(cursor.plusDays(1))) {
            ends.add(cursor);
        }
        return ends;
    }

    /**
     * The house calendar for one crop year.
     *
     * <p>Closed on the fixed holidays, open on the Saturdays of harvest, which for this house runs
     * from the middle of September to the end of November.
     */
    public static BusinessCalendar harvestCalendar(int year) {
        BusinessCalendar calendar = new BusinessCalendar("house-" + year);
        calendar.declareHoliday(LocalDate.of(year, 1, 1));
        calendar.declareHoliday(LocalDate.of(year, 7, 4));
        calendar.declareHoliday(LocalDate.of(year, 12, 25));
        LocalDate last = LocalDate.of(year, 11, 30);
        for (LocalDate cursor = LocalDate.of(year, 9, 15); !cursor.isAfter(last); cursor = cursor.plusDays(1)) {
            if (cursor.getDayOfWeek() == DayOfWeek.SATURDAY) {
                calendar.declareWorkingDay(cursor);
            }
        }
        return calendar;
    }
}
