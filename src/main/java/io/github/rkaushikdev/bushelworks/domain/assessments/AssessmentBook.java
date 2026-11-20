package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Every checkoff the house collects, filtered by state and commodity.
 *
 * <p>The house is the collecting agent. It withholds the assessment from the producer, remits it
 * to the commission and files a return, and it is liable for the money whether or not it
 * remembered to withhold it. That is why the rate table lives in code with tests around it rather
 * than in a spreadsheet on the grain accountant's desk.
 */
public final class AssessmentBook {

    private final List<Assessment> all = new ArrayList<>();

    public AssessmentBook() {}

    public AssessmentBook(Collection<Assessment> assessments) {
        assessments.forEach(this::add);
    }

    /** File a levy. The same code for the same commodity and state is refused. */
    public void add(Assessment assessment) {
        for (Assessment existing : all) {
            if (existing.code().equals(assessment.code())
                    && existing.commodityCode().equals(assessment.commodityCode())
                    && existing.stateCode().equals(assessment.stateCode())) {
                throw new AssessmentError(assessment.code() + " is already on the book");
            }
        }
        all.add(assessment);
    }

    /** The levies on a commodity in a state, by authority and then code. */
    public List<Assessment> applicable(String commodityCode, String stateCode) {
        return all.stream()
                .filter(each -> each.commodityCode().equals(commodityCode) && each.appliesIn(stateCode))
                .sorted(Comparator.comparing(Assessment::authority).thenComparing(Assessment::code))
                .toList();
    }

    /** Every levy that applies, itemised. Levies that come to nothing are left out. */
    public List<WithheldLine> withhold(
            String commodityCode, String stateCode, BigDecimal bushels, BigDecimal grossValue, BigDecimal netValue) {
        return withhold(commodityCode, stateCode, bushels, grossValue, netValue, Set.of());
    }

    /** Every levy that applies, itemised, skipping the ones on file as exempt. */
    public List<WithheldLine> withhold(
            String commodityCode,
            String stateCode,
            BigDecimal bushels,
            BigDecimal grossValue,
            BigDecimal netValue,
            Collection<String> exemptCodes) {
        Set<String> exempt = Set.copyOf(exemptCodes);
        List<WithheldLine> lines = new ArrayList<>();
        for (Assessment assessment : applicable(commodityCode, stateCode)) {
            if (exempt.contains(assessment.code())) {
                continue;
            }
            BigDecimal dollars = assessment.withhold(bushels, grossValue, netValue);
            if (dollars.signum() == 0) {
                continue;
            }
            lines.add(new WithheldLine(
                    assessment.code(), assessment.name(), assessment.authority(), dollars, assessment.refundable()));
        }
        return lines;
    }

    public BigDecimal totalWithheld(List<WithheldLine> lines) {
        return Money.total(lines.stream().map(WithheldLine::dollars).toList());
    }

    /** What the producer could ask back, which he almost never does. */
    public BigDecimal refundablePortion(List<WithheldLine> lines) {
        return Money.total(lines.stream()
                .filter(WithheldLine::refundable)
                .map(WithheldLine::dollars)
                .toList());
    }

    /** Every levy code on the book, once each, sorted. */
    public List<String> codes() {
        return all.stream().map(Assessment::code).distinct().sorted().toList();
    }

    /** The levies this house collects in Kansas and Nebraska. */
    public static AssessmentBook standard() {
        return new AssessmentBook(List.of(
                Assessment.of(
                        "NCB", "National Corn Checkoff", "National Corn Growers", "CORN", new BigDecimal("0.0000")),
                Assessment.of(
                                "KSCORN",
                                "Kansas Corn Commission",
                                "Kansas Department of Agriculture",
                                "CORN",
                                new BigDecimal("0.0100"))
                        .withStateCode("KS")
                        .withRefundable(true),
                Assessment.of(
                                "NECORN",
                                "Nebraska Corn Board",
                                "Nebraska Department of Agriculture",
                                "CORN",
                                new BigDecimal("0.0050"))
                        .withStateCode("NE"),
                Assessment.of("USB", "United Soybean Board", "USDA", "SOYB", new BigDecimal("0.5"))
                        .withBasis(AssessmentBasis.PERCENT_OF_VALUE)
                        .withValueBasis(ValueBasis.NET_OF_DISCOUNTS),
                Assessment.of(
                                "KSWHEAT",
                                "Kansas Wheat Commission",
                                "Kansas Department of Agriculture",
                                "HRW",
                                new BigDecimal("0.0200"))
                        .withStateCode("KS")));
    }
}
