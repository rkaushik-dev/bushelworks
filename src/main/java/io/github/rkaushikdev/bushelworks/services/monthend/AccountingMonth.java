package io.github.rkaushikdev.bushelworks.services.monthend;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One calendar month as an accounting period.
 *
 * @param code the period's code, {@code 2024-10}
 * @param startsOn its first day
 * @param endsOn its last day
 */
public record AccountingMonth(String code, LocalDate startsOn, LocalDate endsOn) {

    public AccountingMonth {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
    }
}
