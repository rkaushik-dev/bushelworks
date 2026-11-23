package io.github.rkaushikdev.bushelworks.services.houses;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.domain.assessments.AssessmentBook;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBook;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountTable;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingTier;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.rulebook.HousePolicy;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.storage.AccrualMethod;
import io.github.rkaushikdev.bushelworks.domain.storage.StorageRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The filed tables this house is running on.
 *
 * <p>These are the schedules on file with the state for the 2024 crop year. They are in code rather
 * than in the database because they are versioned with the code that reads them, and because a rate
 * that changes needs a review and a deploy rather than an update typed at four in the afternoon.
 *
 * <p>When the office wants to change one it files a new version with a later effective date and
 * leaves the old one alone. Nothing here is ever edited in place; a ticket written last October has
 * to settle on last October's table forever.
 */
public final class Houses {

    public static final LocalDate CROP_YEAR_2023 = LocalDate.of(2023, 8, 1);
    public static final LocalDate CROP_YEAR_2024 = LocalDate.of(2024, 8, 1);

    private Houses() {}

    /**
     * The discount board as it hangs by the scale window, a line a band.
     *
     * <p>Light corn pays a cent a half pound down to 52 and two cents a half pound below it; broken
     * corn and foreign material is prorated by the tenth.
     */
    public static final String DISCOUNT_BOARD =
            """
            commodity  factor            start  end   rate    per  mode     label
            CORN       test_weight       54.0   52.0  0.0100  0.5  step     light test weight
            CORN       test_weight       52.0   -     0.0200  0.5  step     light test weight
            CORN       bcfm              3.0    -     0.0020  0.1  prorate  broken corn and foreign material
            CORN       damage_total      5.0    -     0.0100  1.0  step     total damage
            SOYB       foreign_material  1.0    -     0.0150  1.0  step     foreign material
            SOYB       damage_total      3.0    -     0.0200  1.0  step     total damage
            HRW        test_weight       58.0   -     0.0200  1.0  step     light test weight
            HRW        dockage           1.0    -     0.0100  0.5  prorate  dockage over one percent
            """;

    /** Every discount schedule the house publishes on the board. */
    public static DiscountBook discountBook() {
        return new DiscountBook(DiscountTable.parse(DISCOUNT_BOARD));
    }

    /** The corn shrink table at a shrink factor, refusing anything wetter than 25 percent. */
    public static ShrinkSchedule cornShrink(String factor) {
        return ShrinkSchedule.of("CORN", new BigDecimal("15.0"))
                .withMethod(ShrinkMethod.TABLE)
                .withShrinkFactor(new BigDecimal(factor))
                .withRejectAbove(new BigDecimal("25.0"));
    }

    /** The rule book for this house in Kansas with no minimum cheque, both crop years filed. */
    public static RuleBook houseRulebook() {
        return houseRulebook(HousePolicy.of("KS"));
    }

    /** The rule book for this house, its state and minimum cheque taken from the settings. */
    public static RuleBook houseRulebook(Settings settings) {
        return houseRulebook(HousePolicy.of(settings.stateCode()).withMinimumSettlement(settings.minimumSettlement()));
    }

    private static RuleBook houseRulebook(HousePolicy policy) {
        RuleBook book = new RuleBook(policy, discountBook(), AssessmentBook.standard());

        book.fileShrink("CORN", cornShrink("1.20"), CROP_YEAR_2023);
        book.fileShrink("CORN", cornShrink("1.18"), CROP_YEAR_2024);
        book.fileShrink(
                "SOYB",
                ShrinkSchedule.of("SOYB", new BigDecimal("13.0"))
                        .withMethod(ShrinkMethod.TABLE)
                        .withShrinkFactor(new BigDecimal("1.15")),
                CROP_YEAR_2023);
        book.fileShrink(
                "HRW", ShrinkSchedule.of("HRW", new BigDecimal("13.5")).withMethod(ShrinkMethod.NONE), CROP_YEAR_2023);

        book.fileDrying(
                "CORN",
                DryingSchedule.of(
                        "CORN",
                        List.of(
                                DryingTier.upTo(new BigDecimal("2"), new BigDecimal("0.0300")),
                                DryingTier.upTo(new BigDecimal("5"), new BigDecimal("0.0450")),
                                DryingTier.open(new BigDecimal("0.0600")))),
                CROP_YEAR_2023);

        for (String code : List.of("CORN", "SOYB", "HRW")) {
            book.fileStorage(
                    code,
                    StorageRate.of(code, new BigDecimal("0.0450"))
                            .withMethod(AccrualMethod.DAILY)
                            .withFreeDays(30)
                            .withInChargePerBushel(new BigDecimal("0.0900")),
                    CROP_YEAR_2023);
        }
        return book;
    }
}
