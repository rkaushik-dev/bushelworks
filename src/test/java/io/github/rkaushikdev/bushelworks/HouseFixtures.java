package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;

import io.github.rkaushikdev.bushelworks.domain.assessments.AssessmentBook;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBand;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBook;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountMode;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingTier;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.rulebook.HousePolicy;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.storage.AccrualMethod;
import io.github.rkaushikdev.bushelworks.domain.storage.StorageRate;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures modelled on one real house so the numbers can be checked by hand.
 *
 * <p>The house is a two site country elevator in north central Kansas that buys corn, soybeans and
 * hard red winter wheat. Its filed tables are below. Every expected value in the tests can be
 * worked out from these with a pencil, which is the point: a fixture nobody can recompute is a
 * fixture nobody can argue with.
 */
public final class HouseFixtures {

    public static final LocalDate SEASON_OPENS = LocalDate.of(2024, 8, 1);

    private HouseFixtures() {}

    public static DiscountBook discountBook() {
        DiscountSchedule cornTestWeight = new DiscountSchedule(
                "CORN",
                Factor.TEST_WEIGHT,
                List.of(
                        DiscountBand.of(d("54.0"), d("0.0100"))
                                .endingAt(d("52.0"))
                                .withIncrement(d("0.5")),
                        DiscountBand.of(d("52.0"), d("0.0200")).withIncrement(d("0.5"))),
                "light test weight");
        DiscountSchedule cornBcfm = new DiscountSchedule(
                "CORN",
                Factor.BCFM,
                List.of(DiscountBand.of(d("3.0"), d("0.0020"))
                        .withIncrement(d("0.1"))
                        .withMode(DiscountMode.PRORATE)),
                "broken corn and foreign material");
        DiscountSchedule cornDamage = new DiscountSchedule(
                "CORN",
                Factor.DAMAGE_TOTAL,
                List.of(DiscountBand.of(d("5.0"), d("0.0100")).withIncrement(d("1.0"))),
                "total damage");
        DiscountSchedule soyForeign = new DiscountSchedule(
                "SOYB",
                Factor.FOREIGN_MATERIAL,
                List.of(DiscountBand.of(d("1.0"), d("0.0150")).withIncrement(d("1.0"))),
                "foreign material");
        return new DiscountBook(List.of(cornTestWeight, cornBcfm, cornDamage, soyForeign));
    }

    public static RuleBook rules() {
        RuleBook book = new RuleBook(HousePolicy.of("KS"), discountBook(), AssessmentBook.standard());
        book.fileShrink(
                "CORN",
                ShrinkSchedule.of("CORN", d("15.0"))
                        .withMethod(ShrinkMethod.TABLE)
                        .withShrinkFactor(d("1.18"))
                        .withRejectAbove(d("25.0")),
                SEASON_OPENS);
        book.fileShrink(
                "SOYB",
                ShrinkSchedule.of("SOYB", d("13.0"))
                        .withMethod(ShrinkMethod.TABLE)
                        .withShrinkFactor(d("1.15")),
                SEASON_OPENS);
        book.fileShrink("HRW", ShrinkSchedule.of("HRW", d("13.5")).withMethod(ShrinkMethod.NONE), SEASON_OPENS);
        book.fileDrying(
                "CORN",
                DryingSchedule.of(
                        "CORN",
                        List.of(
                                DryingTier.upTo(d("2"), d("0.0300")),
                                DryingTier.upTo(d("5"), d("0.0450")),
                                DryingTier.open(d("0.0600")))),
                SEASON_OPENS);
        book.fileStorage(
                "CORN",
                StorageRate.of("CORN", d("0.0450"))
                        .withMethod(AccrualMethod.DAILY)
                        .withFreeDays(30)
                        .withInChargePerBushel(d("0.0900")),
                SEASON_OPENS);
        return book;
    }

    /** The probe readings on the corn ticket, in the order the grader took them. */
    public static Map<Factor, BigDecimal> cornSample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("56.5"));
        sample.put(Factor.MOISTURE, d("18.2"));
        sample.put(Factor.BCFM, d("1.2"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.0"));
        sample.put(Factor.DAMAGE_HEAT, d("0.0"));
        return sample;
    }

    /** 52,240 lb net of wet corn, one owner, no dockage. */
    public static ScaleTicket cornTicket() {
        return ScaleTicket.builder()
                .ticketNumber("A-10441")
                .scaleDate(LocalDate.of(2024, 10, 3))
                .locationCode("HOME")
                .commodityCode("CORN")
                .grossPounds(d("78420"))
                .tarePounds(d("26180"))
                .disposition(Disposition.CASH_SALE)
                .splits(List.of(new SplitShare("MERRICK", d("100"))))
                .truckId("T-14")
                .driver("R. Merrick")
                .farm("Home place")
                .sample(cornSample())
                .build();
    }

    /** The corn ticket's sample with one reading changed. */
    public static Map<Factor, BigDecimal> cornSampleWith(Factor factor, String value) {
        Map<Factor, BigDecimal> sample = cornSample();
        sample.put(factor, d(value));
        return sample;
    }
}
