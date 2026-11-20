package io.github.rkaushikdev.bushelworks.domain.calendars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CalendarsTest {

    static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    static BusinessCalendar plain() {
        return new BusinessCalendar("plain", Set.of(day(2024, 7, 4)));
    }

    @Test
    void weekendsAreNotBusinessDays() {
        BusinessCalendar cal = plain();
        assertThat(cal.isBusinessDay(day(2024, 10, 4))).isTrue();
        assertThat(cal.isBusinessDay(day(2024, 10, 5))).isFalse();
        assertThat(cal.isBusinessDay(day(2024, 10, 6))).isFalse();
    }

    @Test
    void aDeclaredHolidayIsNotABusinessDay() {
        assertThat(plain().isBusinessDay(day(2024, 7, 4))).isFalse();
    }

    @Test
    void aDeclaredWorkingDayBeatsTheWeekend() {
        BusinessCalendar cal = plain();
        cal.declareWorkingDay(day(2024, 10, 5));
        assertThat(cal.isBusinessDay(day(2024, 10, 5))).isTrue();
    }

    @Test
    void aDayCannotBeBothAHolidayAndAWorkingDay() {
        BusinessCalendar cal = plain();
        assertThatThrownBy(() -> cal.declareWorkingDay(day(2024, 7, 4))).isInstanceOf(ConfigurationError.class);
        BusinessCalendar cal2 = plain();
        cal2.declareWorkingDay(day(2024, 10, 5));
        assertThatThrownBy(() -> cal2.declareHoliday(day(2024, 10, 5))).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aCalendarBuiltWithAClashIsRefused() {
        assertThatThrownBy(() -> new BusinessCalendar(
                        "bad",
                        Set.of(day(2024, 7, 4)),
                        EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                        Set.of(day(2024, 7, 4))))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void nextBusinessDaySkipsTheWeekend() {
        assertThat(plain().nextBusinessDay(day(2024, 10, 4))).isEqualTo(day(2024, 10, 7));
    }

    @Test
    void nextBusinessDaySkipsAHolidayToo() {
        assertThat(plain().nextBusinessDay(day(2024, 7, 3))).isEqualTo(day(2024, 7, 5));
    }

    @Test
    void previousBusinessDayWalksBack() {
        assertThat(plain().previousBusinessDay(day(2024, 10, 7))).isEqualTo(day(2024, 10, 4));
    }

    @Test
    void rollForwardLeavesABusinessDayAlone() {
        BusinessCalendar cal = plain();
        assertThat(cal.rollForward(day(2024, 10, 4))).isEqualTo(day(2024, 10, 4));
        assertThat(cal.rollForward(day(2024, 10, 5))).isEqualTo(day(2024, 10, 7));
    }

    @Test
    void rollBackwardLeavesABusinessDayAlone() {
        BusinessCalendar cal = plain();
        assertThat(cal.rollBackward(day(2024, 10, 4))).isEqualTo(day(2024, 10, 4));
        assertThat(cal.rollBackward(day(2024, 10, 6))).isEqualTo(day(2024, 10, 4));
    }

    @Test
    void addingBusinessDaysCountsOnlyWorkingOnes() {
        BusinessCalendar cal = plain();
        assertThat(cal.addBusinessDays(day(2024, 10, 3), 3)).isEqualTo(day(2024, 10, 8));
        assertThat(cal.addBusinessDays(day(2024, 10, 8), -3)).isEqualTo(day(2024, 10, 3));
    }

    @Test
    void addingZeroBusinessDaysRollsOntoOne() {
        assertThat(plain().addBusinessDays(day(2024, 10, 5), 0)).isEqualTo(day(2024, 10, 7));
    }

    @Test
    void countingBusinessDaysBetweenTwoDates() {
        assertThat(plain().businessDaysBetween(day(2024, 10, 1), day(2024, 10, 8)))
                .isEqualTo(5);
    }

    @Test
    void countingBackwardsIsAnError() {
        assertThatThrownBy(() -> plain().businessDaysBetween(day(2024, 10, 8), day(2024, 10, 1)))
                .isInstanceOf(CalendarError.class);
    }

    @Test
    void monthEndAndMonthStart() {
        assertThat(Calendars.monthEnd(day(2024, 2, 14))).isEqualTo(day(2024, 2, 29));
        assertThat(Calendars.monthEnd(day(2023, 2, 14))).isEqualTo(day(2023, 2, 28));
        assertThat(Calendars.monthEnd(day(2024, 12, 3))).isEqualTo(day(2024, 12, 31));
        assertThat(Calendars.monthStart(day(2024, 10, 17))).isEqualTo(day(2024, 10, 1));
    }

    @Test
    void monthsBetweenCountsWholeMonths() {
        assertThat(Calendars.monthsBetween(day(2024, 1, 15), day(2024, 4, 15))).isEqualTo(3);
        assertThat(Calendars.monthsBetween(day(2024, 1, 15), day(2024, 4, 14))).isEqualTo(2);
        assertThat(Calendars.monthsBetween(day(2024, 4, 15), day(2024, 4, 15))).isZero();
    }

    @Test
    void monthsBetweenNeverGoesNegative() {
        assertThat(Calendars.monthsBetween(day(2024, 4, 15), day(2024, 4, 16))).isZero();
    }

    @Test
    void eachMonthEndWalksTheSpan() {
        assertThat(Calendars.eachMonthEnd(day(2024, 10, 15), day(2025, 1, 15)))
                .containsExactly(day(2024, 10, 31), day(2024, 11, 30), day(2024, 12, 31));
    }

    @Test
    void theHarvestCalendarOpensOnSaturdays() {
        BusinessCalendar cal = Calendars.harvestCalendar(2024);
        assertThat(cal.isBusinessDay(day(2024, 10, 5))).isTrue();
        assertThat(cal.isBusinessDay(day(2024, 10, 6))).isFalse();
        assertThat(cal.isBusinessDay(day(2024, 12, 25))).isFalse();
        assertThat(cal.isBusinessDay(day(2024, 8, 31))).isFalse();
    }
}
