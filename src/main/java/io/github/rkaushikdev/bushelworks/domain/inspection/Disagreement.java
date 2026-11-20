package io.github.rkaushikdev.bushelworks.domain.inspection;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the house grade and the certificate part company.
 *
 * @param factor the factor they disagree on, or nothing when it is the grade itself
 * @param house what the house had
 * @param official what the certificate says
 */
public record Disagreement(Optional<Factor> factor, String house, String official) {

    public Disagreement {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(house, "house");
        Objects.requireNonNull(official, "official");
    }

    public boolean isGrade() {
        return factor.isEmpty();
    }
}
