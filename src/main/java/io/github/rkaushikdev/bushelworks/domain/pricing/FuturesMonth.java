package io.github.rkaushikdev.bushelworks.domain.pricing;

import io.github.rkaushikdev.bushelworks.domain.calendars.BusinessCalendar;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A futures contract month such as CZ4, written here as a commodity, a month code and a year.
 *
 * <p>Month codes are the CME grain codes. Months are ordered by year then by the month letter's
 * position in the delivery cycle, never alphabetically, which is the mistake that puts December
 * before July.
 *
 * <p>The days that matter to the hedge desk are the exchange's, and they are business days on the
 * exchange's calendar: first notice is the last business day of the month before delivery, the
 * first position day the business day before that, and the last trading day the business day
 * before the fifteenth of the delivery month. A short hedge still on at first position day can be
 * handed a delivery notice, which a country elevator does not want.
 *
 * @param commodityCode the commodity the month trades
 * @param monthCode the delivery month letter
 * @param year the four digit year
 */
public record FuturesMonth(String commodityCode, String monthCode, int year) implements Comparable<FuturesMonth> {

    /** Each delivery month letter's calendar month. */
    public static final Map<String, Integer> MONTH_CODES = Map.ofEntries(
            Map.entry("F", 1),
            Map.entry("G", 2),
            Map.entry("H", 3),
            Map.entry("J", 4),
            Map.entry("K", 5),
            Map.entry("M", 6),
            Map.entry("N", 7),
            Map.entry("Q", 8),
            Map.entry("U", 9),
            Map.entry("V", 10),
            Map.entry("X", 11),
            Map.entry("Z", 12));

    /** The months each commodity trades, in cycle order. */
    public static final Map<String, List<String>> DELIVERY_MONTHS = Map.of(
            "CORN", List.of("H", "K", "N", "U", "Z"),
            "SOYB", List.of("F", "H", "K", "N", "Q", "U", "X"),
            "HRW", List.of("H", "K", "N", "U", "Z"),
            "MILO", List.of("H", "K", "N", "U", "Z"));

    /** Year first, then the month's place in the calendar. */
    public static final Comparator<FuturesMonth> ORDER =
            Comparator.comparingInt(FuturesMonth::year).thenComparingInt(FuturesMonth::monthNumber);

    public FuturesMonth {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(monthCode, "monthCode");
        if (!MONTH_CODES.containsKey(monthCode)) {
            throw new PricingError(monthCode + " is not a delivery month code");
        }
        List<String> allowed = DELIVERY_MONTHS.get(commodityCode);
        if (allowed != null && !allowed.contains(monthCode)) {
            throw new PricingError(commodityCode + " does not trade a " + monthCode + " contract");
        }
        if (year < 2000 || year > 2100) {
            throw new PricingError("implausible contract year " + year);
        }
    }

    public int monthNumber() {
        return MONTH_CODES.get(monthCode);
    }

    /** The exchange symbol: commodity, month letter, two digit year. */
    public String symbol() {
        return commodityCode + monthCode + String.format("%02d", year % 100);
    }

    /** The day of the delivery month the last trading day falls before. */
    static final int LAST_TRADE_BEFORE = 15;

    /** Business days between the first position day and first notice. */
    static final int POSITION_DAYS = 1;

    /** Business days from the last trading day to the last delivery day. */
    static final int DELIVERY_DAYS_AFTER_TRADING = 2;

    /** The first of the delivery month, which is where every exchange date is counted from. */
    public LocalDate deliveryMonthStart() {
        return LocalDate.of(year, monthNumber(), 1);
    }

    /** First notice on a calendar with weekends off and no exchange holidays declared. */
    public LocalDate firstNotice() {
        return firstNotice(new BusinessCalendar("exchange"));
    }

    /** First notice: the last business day of the month before the delivery month. */
    public LocalDate firstNotice(BusinessCalendar exchange) {
        return exchange.previousBusinessDay(deliveryMonthStart());
    }

    /** The first position day, when a long still on can be assigned a delivery: the day before first notice. */
    public LocalDate firstPositionDay(BusinessCalendar exchange) {
        return exchange.addBusinessDays(firstNotice(exchange), -POSITION_DAYS);
    }

    /** The last trading day: the business day before the fifteenth of the delivery month. */
    public LocalDate lastTradingDay(BusinessCalendar exchange) {
        return exchange.previousBusinessDay(deliveryMonthStart().withDayOfMonth(LAST_TRADE_BEFORE));
    }

    /** The last delivery day: the second business day after the last trading day. */
    public LocalDate lastDeliveryDay(BusinessCalendar exchange) {
        return exchange.addBusinessDays(lastTradingDay(exchange), DELIVERY_DAYS_AFTER_TRADING);
    }

    /** Whether a short on this month has to be rolled on or before {@code day}, being past first position day. */
    public boolean mustRollBy(LocalDate day, BusinessCalendar exchange) {
        return !day.isBefore(firstPositionDay(exchange));
    }

    public boolean isBefore(FuturesMonth other) {
        return ORDER.compare(this, other) < 0;
    }

    @Override
    public int compareTo(FuturesMonth other) {
        return ORDER.compare(this, other);
    }

    /** The next tradable month for the same commodity. */
    public FuturesMonth next() {
        List<String> cycle = DELIVERY_MONTHS.get(commodityCode);
        if (cycle == null || cycle.isEmpty()) {
            throw new PricingError("no delivery cycle known for " + commodityCode);
        }
        int index = cycle.indexOf(monthCode);
        if (index + 1 < cycle.size()) {
            return new FuturesMonth(commodityCode, cycle.get(index + 1), year);
        }
        return new FuturesMonth(commodityCode, cycle.get(0), year + 1);
    }

    /** Months in delivery order. */
    public static List<FuturesMonth> order(List<FuturesMonth> months) {
        return months.stream().sorted(ORDER).toList();
    }
}
