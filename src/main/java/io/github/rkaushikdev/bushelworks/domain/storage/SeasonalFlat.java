package io.github.rkaushikdev.bushelworks.domain.storage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;

/**
 * A flat storage charge that covers a lot through a fixed day of the year.
 *
 * <p>The commonest tariff on a fall harvest is not a monthly rate at all: so many cents a bushel
 * covers everything delivered through the following June 30, and only grain still in the house
 * after that pays by the month. A load delivered in July for the wheat harvest is covered to the
 * June 30 after it, not the one before.
 *
 * @param perBushel dollars a bushel for the flat period
 * @param through the day of the year the flat period runs to, that day included
 */
public record SeasonalFlat(BigDecimal perBushel, MonthDay through) {

    public SeasonalFlat {
        Objects.requireNonNull(perBushel, "perBushel");
        Objects.requireNonNull(through, "through");
        if (perBushel.signum() <= 0) {
            throw new StorageError("a flat storage charge has to be more than nothing");
        }
    }

    /** The last day the flat charge covers a lot received on {@code storedOn}. */
    public LocalDate coversThrough(LocalDate storedOn) {
        LocalDate sameYear = through.atYear(storedOn.getYear());
        return sameYear.isBefore(storedOn) ? through.atYear(storedOn.getYear() + 1) : sameYear;
    }
}
