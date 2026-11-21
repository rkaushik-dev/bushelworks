package io.github.rkaushikdev.bushelworks.api.schemas;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a load came to once it was graded: the bushels at each step from the scale to the settlement,
 * and the grade.
 *
 * @param ticketNumber the ticket
 * @param commodityCode the commodity
 * @param grossBushels bushels before dockage
 * @param cleanBushels bushels after dockage
 * @param dryBushels bushels after moisture shrink, the ones that are paid for
 * @param shrinkBushels the bushels the shrink took
 * @param shrinkPercent the shrink, as a percentage of the clean bushels
 * @param moisturePoints how far the moisture reading was over the filed base
 * @param discountRate every quality discount added up, per bushel
 * @param grade the grade the sample made
 */
public record GradedLoadOut(
        String ticketNumber,
        String commodityCode,
        BigDecimal grossBushels,
        BigDecimal cleanBushels,
        BigDecimal dryBushels,
        BigDecimal shrinkBushels,
        BigDecimal shrinkPercent,
        BigDecimal moisturePoints,
        BigDecimal discountRate,
        GradeOut grade) {

    public GradedLoadOut {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(grade, "grade");
    }
}
