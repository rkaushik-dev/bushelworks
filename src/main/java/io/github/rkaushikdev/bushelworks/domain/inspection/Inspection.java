package io.github.rkaushikdev.bushelworks.domain.inspection;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Comparing what the house called a load with what the inspector called it. */
public final class Inspection {

    private Inspection() {}

    /** Whether the house grade and the certificate's grade agree; a disagreement when they do not. */
    public static List<Disagreement> compare(GradeResult house, Certificate certificate) {
        List<Disagreement> found = new ArrayList<>();
        if (house.grade() != certificate.grade()) {
            found.add(new Disagreement(
                    Optional.empty(), house.grade().value(), certificate.grade().value()));
        }
        return found;
    }

    /** Factor by factor, with no tolerance at all. */
    public static List<Disagreement> compareFactors(Map<Factor, BigDecimal> houseSample, Certificate certificate) {
        return compareFactors(houseSample, certificate, Map.of());
    }

    /**
     * Factor by factor, inside a tolerance the house sets per factor, in the order of the factors'
     * values.
     *
     * <p>Factors the house never measured are not disagreements. It cannot disagree about a number it
     * did not take, and reporting those as differences buries the ones that matter.
     */
    public static List<Disagreement> compareFactors(
            Map<Factor, BigDecimal> houseSample, Certificate certificate, Map<Factor, BigDecimal> tolerance) {
        List<Disagreement> found = new ArrayList<>();
        List<Factor> factors =
                certificate.factors().keySet().stream().sorted(Factor.BY_VALUE).toList();
        for (Factor factor : factors) {
            BigDecimal official = certificate.factors().get(factor);
            BigDecimal ours = houseSample.get(factor);
            if (ours == null) {
                continue;
            }
            BigDecimal gap = ours.subtract(official).abs();
            if (gap.compareTo(tolerance.getOrDefault(factor, BigDecimal.ZERO)) > 0) {
                found.add(new Disagreement(Optional.of(factor), ours.toString(), official.toString()));
            }
        }
        return found;
    }

    /**
     * What the difference in grade costs per bushel.
     *
     * <p>Positive means the certificate came back worse than the house called it, which is the
     * direction that costs money.
     */
    public static BigDecimal gradeLoss(
            GradeResult house, Certificate certificate, Map<NumericalGrade, BigDecimal> prices) {
        BigDecimal ours = prices.get(house.grade());
        BigDecimal theirs = prices.get(certificate.grade());
        if (ours == null || theirs == null) {
            throw new InspectionError("no posted price for one of the grades being compared");
        }
        return ours.subtract(theirs);
    }

    /** The poorest certificate in a set, which is what a blend has to answer for. */
    public static Certificate worstOf(List<Certificate> certificates) {
        if (certificates.isEmpty()) {
            throw new InspectionError("no certificates to compare");
        }
        return certificates.stream()
                .max(Comparator.comparingInt((Certificate one) -> one.grade().rank())
                        .thenComparing(Certificate::number))
                .orElseThrow();
    }
}
