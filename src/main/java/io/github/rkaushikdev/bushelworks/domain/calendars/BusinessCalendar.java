package io.github.rkaushikdev.bushelworks.domain.calendars;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Which days the office counts as working days.
 *
 * <p>Storage free periods are counted in plain days, but cheque runs, futures rolls and the daily
 * position close all move to the next business day, and a country elevator's business days are not
 * the bank's. Harvest is the obvious case: the scale runs on the Saturday after Thanksgiving
 * whatever the calendar says.
 *
 * <p>Holidays are declared rather than computed. A rule that derives Thanksgiving from the fourth
 * Thursday is correct and useless, because the house closes when the manager says it closes.
 */
public final class BusinessCalendar {

    private static final int LOOK_AHEAD_DAYS = 400;

    private final String name;
    private final Set<LocalDate> holidays;
    private final Set<DayOfWeek> weekendDays;
    private final Set<LocalDate> extraWorkingDays;

    /** A calendar with Saturday and Sunday off and nothing else declared. */
    public BusinessCalendar(String name) {
        this(name, Set.of(), EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of());
    }

    /** A calendar with Saturday and Sunday off and these holidays. */
    public BusinessCalendar(String name, Set<LocalDate> holidays) {
        this(name, holidays, EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of());
    }

    public BusinessCalendar(
            String name, Set<LocalDate> holidays, Set<DayOfWeek> weekendDays, Set<LocalDate> extraWorkingDays) {
        this.name = name;
        this.holidays = new HashSet<>(holidays);
        this.weekendDays = weekendDays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekendDays);
        this.extraWorkingDays = new HashSet<>(extraWorkingDays);
        Set<LocalDate> clash = new TreeSet<>(this.holidays);
        clash.retainAll(this.extraWorkingDays);
        if (!clash.isEmpty()) {
            String listed = clash.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
            throw new ConfigurationError(name + ": " + listed + " is both a holiday and a working day");
        }
    }

    public String name() {
        return name;
    }

    public Set<LocalDate> holidays() {
        return Set.copyOf(holidays);
    }

    public boolean isBusinessDay(LocalDate day) {
        if (extraWorkingDays.contains(day)) {
            return true;
        }
        if (holidays.contains(day)) {
            return false;
        }
        return !weekendDays.contains(day.getDayOfWeek());
    }

    public LocalDate nextBusinessDay(LocalDate day) {
        return walk(day, next -> next.plusDays(1), "within a year of");
    }

    public LocalDate previousBusinessDay(LocalDate day) {
        return walk(day, next -> next.minusDays(1), "within a year before");
    }

    private LocalDate walk(LocalDate day, UnaryOperator<LocalDate> step, String how) {
        LocalDate cursor = step.apply(day);
        for (int tried = 0; tried < LOOK_AHEAD_DAYS; tried++) {
            if (isBusinessDay(cursor)) {
                return cursor;
            }
            cursor = step.apply(cursor);
        }
        throw new CalendarError(name + ": no business day " + how + " " + day);
    }

    /** The day itself if it works, otherwise the next one that does. */
    public LocalDate rollForward(LocalDate day) {
        return isBusinessDay(day) ? day : nextBusinessDay(day);
    }

    /** The day itself if it works, otherwise the last one before it that does. */
    public LocalDate rollBackward(LocalDate day) {
        return isBusinessDay(day) ? day : previousBusinessDay(day);
    }

    /** {@code count} working days on from {@code day}, or back for a negative count. */
    public LocalDate addBusinessDays(LocalDate day, int count) {
        if (count == 0) {
            return rollForward(day);
        }
        LocalDate cursor = day;
        for (int step = 0; step < Math.abs(count); step++) {
            cursor = count > 0 ? nextBusinessDay(cursor) : previousBusinessDay(cursor);
        }
        return cursor;
    }

    /** Working days after {@code start} up to and including {@code end}. */
    public int businessDaysBetween(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new CalendarError(name + ": " + end + " is before " + start);
        }
        int count = 0;
        for (LocalDate cursor = start.plusDays(1); !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            if (isBusinessDay(cursor)) {
                count++;
            }
        }
        return count;
    }

    public void declareHoliday(LocalDate day) {
        if (extraWorkingDays.contains(day)) {
            throw new ConfigurationError(name + ": " + day + " is already declared a working day");
        }
        holidays.add(day);
    }

    public void declareWorkingDay(LocalDate day) {
        if (holidays.contains(day)) {
            throw new ConfigurationError(name + ": " + day + " is already a holiday");
        }
        extraWorkingDays.add(day);
    }
}
