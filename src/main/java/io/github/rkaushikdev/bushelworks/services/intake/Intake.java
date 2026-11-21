package io.github.rkaushikdev.bushelworks.services.intake;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.Grading;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Turn a scale ticket into a graded, shrunk, discounted load.
 *
 * <p>This is the first half of settlement and the half that has no money in it yet. It answers four
 * questions in order and each answer feeds the next:
 *
 * <ol>
 *   <li>how many bushels came off the truck, after dockage
 *   <li>what grade the sample is
 *   <li>how many of those bushels survive moisture shrink
 *   <li>what the quality discounts come to, per bushel
 * </ol>
 *
 * <p>Some houses discount the wet bushels and some the dry, and the difference on a wet harvest is
 * real money, so the discounts leave here per bushel and settlement extends them.
 */
public final class Intake {

    private Intake() {}

    /**
     * Run one ticket through grading, shrink and the discount book.
     *
     * @throws IntakeError when the ticket is voided, or the house has no shrink schedule on file for
     *     its commodity, site and day
     */
    public static GradedLoad receive(ScaleTicket ticket, RuleBook rules) {
        if (ticket.voided()) {
            throw new IntakeError(ticket.ticketNumber() + ": ticket is voided");
        }

        Commodity commodity = Commodities.get(ticket.commodityCode());
        if (!rules.covers(ticket.commodityCode(), ticket.scaleDate(), ticket.locationCode())) {
            throw new IntakeError(ticket.ticketNumber() + ": no shrink schedule on file for " + ticket.commodityCode()
                    + " at " + ticket.locationCode() + " on " + ticket.scaleDate());
        }

        GradeResult grade = Grading.determine(commodity, ticket.sample());
        ShrinkSchedule schedule = rules.shrinkFor(ticket.commodityCode(), ticket.scaleDate(), ticket.locationCode());
        BigDecimal moisture = ticket.requireFactor(Factor.MOISTURE);

        BigDecimal clean = ticket.cleanBushels(commodity);
        BigDecimal dry = schedule.dryBushels(clean, moisture);
        BigDecimal shrunk = Units.quantize(clean.subtract(dry), Units.BUSHEL_PLACES);

        return new GradedLoad(
                ticket.ticketNumber(),
                ticket.commodityCode(),
                ticket.scaleDate(),
                ticket.locationCode(),
                ticket.netPounds(),
                ticket.dockagePounds(),
                ticket.grossBushels(commodity),
                clean,
                dry,
                shrunk,
                schedule.shrinkPercent(moisture),
                schedule.pointsOver(moisture),
                grade,
                rules.discounts().totalRate(ticket.commodityCode(), ticket.sample()),
                rules.discounts().breakdown(ticket.commodityCode(), ticket.sample()));
    }

    /** What the dryer costs on this load, worked on the wet bushels. */
    public static BigDecimal dryingCharge(GradedLoad load, RuleBook rules) {
        return dryingCharge(load, rules, false);
    }

    /**
     * What the dryer costs on this load, if the house has one on file; nothing when the load came in
     * at or under the base moisture.
     */
    public static BigDecimal dryingCharge(GradedLoad load, RuleBook rules, boolean onDryBushels) {
        Optional<DryingSchedule> schedule =
                rules.dryingFor(load.commodityCode(), load.scaleDate(), load.locationCode());
        if (schedule.isEmpty() || load.moisturePoints().signum() <= 0) {
            return Money.ZERO_AMOUNT;
        }
        BigDecimal bushels = onDryBushels ? load.dryBushels() : load.cleanBushels();
        return schedule.get().charge(bushels, load.moisturePoints());
    }

    /**
     * Recompute shrink on a corrected moisture reading.
     *
     * <p>The moisture meter is the instrument most likely to be wrong and the one most likely to be
     * rechecked while the truck is still on the lot. Rather than void and rewrite the ticket, intake
     * restates the load, and settlement picks up whichever version it is handed.
     */
    public static GradedLoad restateFor(GradedLoad load, ShrinkSchedule schedule, BigDecimal moisture) {
        BigDecimal dry = schedule.dryBushels(load.cleanBushels(), moisture);
        return load.withShrink(
                dry,
                Units.quantize(load.cleanBushels().subtract(dry), Units.BUSHEL_PLACES),
                schedule.shrinkPercent(moisture),
                schedule.pointsOver(moisture));
    }
}
