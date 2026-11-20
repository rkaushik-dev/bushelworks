package io.github.rkaushikdev.bushelworks.domain.inspection;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One official inspection certificate, as the agency issued it.
 *
 * <p>The house grades its own purchases. A sale to a terminal or an export elevator is graded by a
 * licensed inspector, and the certificate that comes back decides what the buyer pays. When the two
 * disagree the certificate wins, and the difference between them is a real cost the house carries.
 *
 * @param number the agency's certificate number, which is the key
 * @param agency who inspected
 * @param commodityCode the commodity
 * @param sampledOn when the sample was drawn
 * @param issuedOn when the certificate was issued
 * @param grade the numerical grade
 * @param factors the factors as the inspector found them, which may include ones the house never
 *     measured
 * @param specialGrades the words after the number
 * @param reference the house's own reference for the load, if it gave one
 */
public record Certificate(
        String number,
        String agency,
        String commodityCode,
        LocalDate sampledOn,
        LocalDate issuedOn,
        NumericalGrade grade,
        Map<Factor, BigDecimal> factors,
        Set<SpecialGrade> specialGrades,
        Optional<String> reference) {

    /** How long a certificate stands after its sample, in days; then the buyer can call a reinspection. */
    public static final int CERTIFICATE_LIFE_DAYS = 30;

    public Certificate {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(agency, "agency");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(sampledOn, "sampledOn");
        Objects.requireNonNull(issuedOn, "issuedOn");
        Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(reference, "reference");
        factors = Ordered.copyOf(factors);
        specialGrades = Set.copyOf(specialGrades);
        if (number.isEmpty()) {
            throw new InspectionError("a certificate needs a number");
        }
        if (agency.isEmpty()) {
            throw new InspectionError(number + ": a certificate needs an agency");
        }
        if (issuedOn.isBefore(sampledOn)) {
            throw new InspectionError(number + ": issued before the sample was drawn");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .number(number)
                .agency(agency)
                .commodityCode(commodityCode)
                .sampledOn(sampledOn)
                .issuedOn(issuedOn)
                .grade(grade)
                .factors(factors)
                .specialGrades(specialGrades)
                .reference(reference.orElse(null));
    }

    public LocalDate expiresOn() {
        return sampledOn.plusDays(CERTIFICATE_LIFE_DAYS);
    }

    public boolean isCurrentOn(LocalDate day) {
        return !day.isBefore(issuedOn) && !day.isAfter(expiresOn());
    }

    /** Whether a buyer can refuse the load outright on this certificate. */
    public boolean isRejectable() {
        return specialGrades.stream().anyMatch(SpecialGrade.REJECTING::contains);
    }

    /** What the certificate says, the special grades in alphabetical order after the grade. */
    public String description() {
        String words = specialGrades.stream()
                .map(special -> special.value().replace('_', ' '))
                .sorted()
                .collect(Collectors.joining(", "));
        String base = grade == NumericalGrade.SAMPLE
                ? "Sample Grade " + commodityCode
                : "US No. " + grade.value() + " " + commodityCode;
        return words.isEmpty() ? base : base + ", " + words;
    }

    public Optional<BigDecimal> factor(Factor factor) {
        return Optional.ofNullable(factors.get(factor));
    }

    /** A certificate assembled a field at a time; no factors, special grades or reference unless told. */
    public static final class Builder {

        private String number;
        private String agency;
        private String commodityCode;
        private LocalDate sampledOn;
        private LocalDate issuedOn;
        private NumericalGrade grade;
        private Map<Factor, BigDecimal> factors = Map.of();
        private Set<SpecialGrade> specialGrades = Set.of();
        private String reference;

        private Builder() {}

        public Builder number(String value) {
            this.number = value;
            return this;
        }

        public Builder agency(String value) {
            this.agency = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder sampledOn(LocalDate value) {
            this.sampledOn = value;
            return this;
        }

        public Builder issuedOn(LocalDate value) {
            this.issuedOn = value;
            return this;
        }

        public Builder grade(NumericalGrade value) {
            this.grade = value;
            return this;
        }

        public Builder factors(Map<Factor, BigDecimal> value) {
            this.factors = value;
            return this;
        }

        public Builder specialGrades(Set<SpecialGrade> value) {
            this.specialGrades = value;
            return this;
        }

        /** The house's reference, or null for none. */
        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Certificate build() {
            return new Certificate(
                    number,
                    agency,
                    commodityCode,
                    sampledOn,
                    issuedOn,
                    grade,
                    factors,
                    specialGrades,
                    Optional.ofNullable(reference));
        }
    }
}
