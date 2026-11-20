package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every discount schedule a house publishes, keyed by commodity and factor. */
public final class DiscountBook {

    private record Key(String commodityCode, Factor factor) {}

    private final Map<Key, DiscountSchedule> byKey = new LinkedHashMap<>();

    public DiscountBook() {}

    public DiscountBook(Collection<DiscountSchedule> schedules) {
        schedules.forEach(this::add);
    }

    /** File a schedule. A second one for the same commodity and factor is refused. */
    public void add(DiscountSchedule schedule) {
        Key key = new Key(schedule.commodityCode(), schedule.factor());
        if (byKey.containsKey(key)) {
            throw new ScheduleError("two schedules for " + key.commodityCode() + "/"
                    + key.factor().value());
        }
        byKey.put(key, schedule);
    }

    public Optional<DiscountSchedule> forFactor(String commodityCode, Factor factor) {
        return Optional.ofNullable(byKey.get(new Key(commodityCode, factor)));
    }

    /** The factors a commodity has schedules for, in the order of their values. */
    public List<Factor> factors(String commodityCode) {
        return byKey.keySet().stream()
                .filter(key -> key.commodityCode().equals(commodityCode))
                .map(Key::factor)
                .sorted(Factor.BY_VALUE)
                .toList();
    }

    /** Every schedule on file, in the order they were filed. */
    public List<DiscountSchedule> schedules() {
        return List.copyOf(byKey.values());
    }

    /**
     * Every discount that applies to this sample, added up.
     *
     * <p>Discounts add. There is no worst factor rule here and that is the point: grade takes the
     * poorest factor, money takes all of them.
     */
    public BigDecimal totalRate(String commodityCode, Map<Factor, BigDecimal> sample) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Factor, BigDecimal> reading : sample.entrySet()) {
            Optional<DiscountSchedule> schedule = forFactor(commodityCode, reading.getKey());
            if (schedule.isPresent()) {
                total = total.add(schedule.get().rateFor(reading.getValue()));
            }
        }
        return Units.quantize(total, Money.PRICE_PLACES);
    }

    /** The same thing, itemised, because a producer will ask. Factors that cost nothing are left out. */
    public Map<Factor, BigDecimal> breakdown(String commodityCode, Map<Factor, BigDecimal> sample) {
        Map<Factor, BigDecimal> out = new LinkedHashMap<>();
        sample.forEach((factor, value) -> forFactor(commodityCode, factor).ifPresent(schedule -> {
            BigDecimal rate = schedule.rateFor(value);
            if (rate.signum() != 0) {
                out.put(factor, rate);
            }
        }));
        return out;
    }
}
