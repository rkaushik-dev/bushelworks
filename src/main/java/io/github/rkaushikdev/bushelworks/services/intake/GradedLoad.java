package io.github.rkaushikdev.bushelworks.services.intake;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * What intake knows about a load before anybody prices it.
 *
 * <p>Discounts are quoted per bushel rather than in dollars, because which bushel count they are
 * extended over is a settlement decision, not an intake one.
 *
 * @param ticketNumber the ticket taken in
 * @param commodityCode what was on the truck
 * @param scaleDate the day it crossed the scale
 * @param locationCode where
 * @param netPounds gross less tare
 * @param dockagePounds what the cleaner took out
 * @param grossBushels bushels before dockage
 * @param cleanBushels bushels after dockage, before shrink
 * @param dryBushels bushels after moisture shrink
 * @param shrinkBushels the bushels the shrink took
 * @param shrinkPercent the shrink, as a percentage of the clean bushels
 * @param moisturePoints how far the moisture reading is over the filed base
 * @param grade the grade the sample made
 * @param discountRate every quality discount added up, per bushel
 * @param discountDetail the discounts per bushel, factor by factor, in sample order
 */
public record GradedLoad(
        String ticketNumber,
        String commodityCode,
        LocalDate scaleDate,
        String locationCode,
        BigDecimal netPounds,
        BigDecimal dockagePounds,
        BigDecimal grossBushels,
        BigDecimal cleanBushels,
        BigDecimal dryBushels,
        BigDecimal shrinkBushels,
        BigDecimal shrinkPercent,
        BigDecimal moisturePoints,
        GradeResult grade,
        BigDecimal discountRate,
        Map<Factor, BigDecimal> discountDetail) {

    public GradedLoad {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(scaleDate, "scaleDate");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(netPounds, "netPounds");
        Objects.requireNonNull(dockagePounds, "dockagePounds");
        Objects.requireNonNull(grossBushels, "grossBushels");
        Objects.requireNonNull(cleanBushels, "cleanBushels");
        Objects.requireNonNull(dryBushels, "dryBushels");
        Objects.requireNonNull(shrinkBushels, "shrinkBushels");
        Objects.requireNonNull(shrinkPercent, "shrinkPercent");
        Objects.requireNonNull(moisturePoints, "moisturePoints");
        Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(discountRate, "discountRate");
        discountDetail = Ordered.copyOf(discountDetail);
    }

    /** The bushel count everything downstream prices. */
    public BigDecimal settledBushels() {
        return dryBushels;
    }

    public boolean isSampleGrade() {
        return grade.isSampleGrade();
    }

    /** The same load with its shrink worked again on another moisture reading. */
    GradedLoad withShrink(BigDecimal dry, BigDecimal shrunk, BigDecimal percent, BigDecimal points) {
        return new GradedLoad(
                ticketNumber,
                commodityCode,
                scaleDate,
                locationCode,
                netPounds,
                dockagePounds,
                grossBushels,
                cleanBushels,
                dry,
                shrunk,
                percent,
                points,
                grade,
                discountRate,
                discountDetail);
    }
}
