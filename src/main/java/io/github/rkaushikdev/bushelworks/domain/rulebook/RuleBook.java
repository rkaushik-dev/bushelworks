package io.github.rkaushikdev.bushelworks.domain.rulebook;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.domain.assessments.AssessmentBook;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBook;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.storage.StorageRate;
import io.github.rkaushikdev.bushelworks.schedules.ScheduleRegistry;
import io.github.rkaushikdev.bushelworks.schedules.Version;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one house has filed, in one object: the tables resolved by commodity, site and date.
 *
 * <p>Each piece of the settlement arithmetic already knows how to do its own job. What it does not
 * know is which version of itself applies to a given ticket, and that depends on the commodity, the
 * elevator and the date on the ticket rather than on today. The rule book holds all of those
 * registries together and answers one question: for this ticket, which tables?
 *
 * <p>Nothing here computes money. It hands back the tables that do, which keeps the resolution
 * rules in one place and testable on their own.
 */
public final class RuleBook {

    private final HousePolicy policy;
    private final DiscountBook discounts;
    private final AssessmentBook assessments;
    private final ScheduleRegistry<ShrinkSchedule> shrink = new ScheduleRegistry<>("shrink");
    private final ScheduleRegistry<DryingSchedule> drying = new ScheduleRegistry<>("drying");
    private final ScheduleRegistry<StorageRate> storage = new ScheduleRegistry<>("storage");

    /** A book with no discounts and no assessments on it. */
    public RuleBook(HousePolicy policy) {
        this(policy, new DiscountBook(), new AssessmentBook());
    }

    public RuleBook(HousePolicy policy, DiscountBook discounts, AssessmentBook assessments) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
    }

    public HousePolicy policy() {
        return policy;
    }

    public DiscountBook discounts() {
        return discounts;
    }

    public AssessmentBook assessments() {
        return assessments;
    }

    public ScheduleRegistry<ShrinkSchedule> shrink() {
        return shrink;
    }

    public ScheduleRegistry<DryingSchedule> drying() {
        return drying;
    }

    public ScheduleRegistry<StorageRate> storage() {
        return storage;
    }

    /** File a company wide shrink schedule. */
    public void fileShrink(String commodityCode, ShrinkSchedule schedule, LocalDate effectiveFrom) {
        fileShrink(commodityCode, schedule, effectiveFrom, ScheduleRegistry.COMPANY_WIDE);
    }

    public void fileShrink(String commodityCode, ShrinkSchedule schedule, LocalDate effectiveFrom, String location) {
        if (!schedule.commodityCode().equals(commodityCode)) {
            throw new ConfigurationError(
                    "shrink schedule for " + schedule.commodityCode() + " filed under " + commodityCode);
        }
        shrink.file(commodityCode, Version.of(effectiveFrom, schedule).at(location));
    }

    /** File a company wide drying schedule. */
    public void fileDrying(String commodityCode, DryingSchedule schedule, LocalDate effectiveFrom) {
        fileDrying(commodityCode, schedule, effectiveFrom, ScheduleRegistry.COMPANY_WIDE);
    }

    public void fileDrying(String commodityCode, DryingSchedule schedule, LocalDate effectiveFrom, String location) {
        if (!schedule.commodityCode().equals(commodityCode)) {
            throw new ConfigurationError(
                    "drying schedule for " + schedule.commodityCode() + " filed under " + commodityCode);
        }
        drying.file(commodityCode, Version.of(effectiveFrom, schedule).at(location));
    }

    /** File a company wide storage rate. */
    public void fileStorage(String commodityCode, StorageRate rate, LocalDate effectiveFrom) {
        fileStorage(commodityCode, rate, effectiveFrom, ScheduleRegistry.COMPANY_WIDE);
    }

    public void fileStorage(String commodityCode, StorageRate rate, LocalDate effectiveFrom, String location) {
        if (!rate.commodityCode().equals(commodityCode)) {
            throw new ConfigurationError("storage rate for " + rate.commodityCode() + " filed under " + commodityCode);
        }
        storage.file(commodityCode, Version.of(effectiveFrom, rate).at(location));
    }

    public ShrinkSchedule shrinkFor(String commodityCode, LocalDate asOf) {
        return shrink.resolve(commodityCode, asOf);
    }

    public ShrinkSchedule shrinkFor(String commodityCode, LocalDate asOf, String location) {
        return shrink.resolve(commodityCode, asOf, location);
    }

    public Optional<DryingSchedule> dryingFor(String commodityCode, LocalDate asOf) {
        return dryingFor(commodityCode, asOf, ScheduleRegistry.COMPANY_WIDE);
    }

    /** Drying is optional. A house with no dryer files nothing and owes nothing. */
    public Optional<DryingSchedule> dryingFor(String commodityCode, LocalDate asOf, String location) {
        return drying.maybeResolve(commodityCode, asOf, location);
    }

    public Optional<StorageRate> storageFor(String commodityCode, LocalDate asOf) {
        return storageFor(commodityCode, asOf, ScheduleRegistry.COMPANY_WIDE);
    }

    public Optional<StorageRate> storageFor(String commodityCode, LocalDate asOf, String location) {
        return storage.maybeResolve(commodityCode, asOf, location);
    }

    /**
     * Whether this house can settle the commodity at all on that day.
     *
     * <p>Shrink is the test. A house can trade without a dryer, without storage and without a
     * discount table, but it cannot turn wet bushels into dry ones without a filed shrink schedule,
     * and settling on an unfiled table is how a house ends up refunding a whole harvest.
     */
    public boolean covers(String commodityCode, LocalDate asOf, String location) {
        return shrink.maybeResolve(commodityCode, asOf, location).isPresent();
    }
}
