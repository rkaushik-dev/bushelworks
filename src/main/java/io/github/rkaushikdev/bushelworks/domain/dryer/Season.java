package io.github.rkaushikdev.bushelworks.domain.dryer;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A season of dryer runs, costed together: what a point of moisture actually earned.
 *
 * <p>The house charges a published rate per point per bushel. What it pays is propane and
 * electricity, and the two have nothing to do with each other. Whether drying made money in a wet
 * year is a question the manager asks every November, and this puts the two side by side.
 *
 * @param opens the first day of the season
 * @param closes the last day of the season
 * @param runs the runs started inside it
 * @param ratePerPoint the published drying rate
 * @param propanePrice what a gallon cost
 * @param powerPrice what a kilowatt hour cost
 */
public record Season(
        LocalDate opens,
        LocalDate closes,
        List<Run> runs,
        BigDecimal ratePerPoint,
        BigDecimal propanePrice,
        BigDecimal powerPrice) {

    private static final BigDecimal CENTS = new BigDecimal("0.01");
    private static final BigDecimal RATE_PLACES = new BigDecimal("0.0001");

    public Season {
        runs = List.copyOf(runs);
    }

    /** The season between two days, holding the runs started inside it. */
    public static Season of(
            List<Run> runs,
            LocalDate opens,
            LocalDate closes,
            BigDecimal ratePerPoint,
            BigDecimal propanePrice,
            BigDecimal powerPrice) {
        if (closes.isBefore(opens)) {
            throw new DryerError("a season closes after it opens");
        }
        List<Run> inside = runs.stream()
                .filter(run -> {
                    LocalDate started = run.startedAt().toLocalDate();
                    return !started.isBefore(opens) && !started.isAfter(closes);
                })
                .toList();
        return new Season(opens, closes, inside, ratePerPoint, propanePrice, powerPrice);
    }

    private BigDecimal sum(Function<Run, BigDecimal> each) {
        BigDecimal total = BigDecimal.ZERO;
        for (Run run : runs) {
            total = total.add(each.apply(run));
        }
        return total;
    }

    public BigDecimal bushels() {
        return Units.quantize(sum(Run::bushels), Units.BUSHEL_PLACES);
    }

    public BigDecimal pointBushels() {
        return Units.quantize(sum(Run::pointBushels), CENTS);
    }

    public BigDecimal revenue() {
        return Money.amount(sum(run -> run.revenue(ratePerPoint)));
    }

    public BigDecimal cost() {
        return Money.amount(sum(run -> run.cost(propanePrice, powerPrice)));
    }

    public BigDecimal margin() {
        return Money.amount(revenue().subtract(cost()));
    }

    public BigDecimal hours() {
        return Units.quantize(sum(Run::hours), CENTS);
    }

    public List<Run> idleRuns() {
        return runs.stream().filter(Run::isIdle).toList();
    }

    public List<Run> thirstyRuns() {
        return runs.stream().filter(Run::isThirsty).toList();
    }

    /** What the house would have to charge to come out level, or nothing when nothing was dried. */
    public Optional<BigDecimal> breakEvenRate() {
        BigDecimal pointBushels = pointBushels();
        if (pointBushels.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Units.quantize(Decimals.divide(cost(), pointBushels), RATE_PLACES));
    }
}
