package io.github.rkaushikdev.bushelworks.services.desk;

import io.github.rkaushikdev.bushelworks.domain.hedging.HedgeError;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The whole desk on one morning: a gap per commodity on the position.
 *
 * @param asOf the day
 * @param locationCode the site, or {@code *} for the whole house
 * @param gaps one per commodity, in commodity order
 */
public record Sheet(LocalDate asOf, String locationCode, List<Gap> gaps) {

    public Sheet {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(locationCode, "locationCode");
        gaps = List.copyOf(gaps);
    }

    /** The gaps worth a telephone call. */
    public List<Gap> actionable() {
        return gaps.stream().filter(Gap::isActionable).toList();
    }

    /** The commodities short more than the house owns. */
    public List<Gap> overHedged() {
        return gaps.stream().filter(Gap::isOverHedged).toList();
    }

    /**
     * The gap for one commodity.
     *
     * @throws HedgeError when the commodity is not on the sheet
     */
    public Gap forCommodity(String commodityCode) {
        return gaps.stream()
                .filter(gap -> gap.commodityCode().equals(commodityCode))
                .findFirst()
                .orElseThrow(() -> new HedgeError(commodityCode + " is not on this sheet"));
    }

    /**
     * Net across the board. Two commodities can offset on the ticket and never offset in the bin, so
     * this is a headline and not a plan.
     */
    public int totalGapContracts() {
        return gaps.stream().mapToInt(Gap::gapContracts).sum();
    }
}
