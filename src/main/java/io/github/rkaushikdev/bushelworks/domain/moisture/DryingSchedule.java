package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A tiered drying charge.
 *
 * <p>Tiers are cumulative bands, not a lookup: a load four points over a schedule with a two point
 * first tier pays the first tier on two points and the second tier on the other two. Houses that
 * quote a flat rate simply publish one tier with no ceiling.
 *
 * <p>Shrink and drying are separate charges. Shrink takes bushels away, drying takes money away,
 * and a load can be subject to one, both or neither.
 *
 * @param commodityCode the commodity
 * @param tiers the tiers, lowest first, the open ended one last
 * @param basis which bushel count the rate is applied to
 * @param minimumCharge the least a load that owes anything pays
 * @param freePoints points over base the house dries for nothing
 */
public record DryingSchedule(
        String commodityCode,
        List<DryingTier> tiers,
        DryingBasis basis,
        BigDecimal minimumCharge,
        BigDecimal freePoints) {

    public DryingSchedule {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(minimumCharge, "minimumCharge");
        Objects.requireNonNull(freePoints, "freePoints");
        tiers = List.copyOf(tiers);
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException(commodityCode + ": drying schedule has no tiers");
        }
        boolean seenOpen = false;
        BigDecimal last = BigDecimal.ZERO;
        for (DryingTier tier : tiers) {
            if (seenOpen) {
                throw new IllegalArgumentException(commodityCode + ": a tier follows the open ended tier");
            }
            if (tier.upToPoints().isEmpty()) {
                seenOpen = true;
                continue;
            }
            if (tier.upToPoints().get().compareTo(last) <= 0) {
                throw new IllegalArgumentException(commodityCode + ": drying tiers are out of order");
            }
            last = tier.upToPoints().get();
        }
    }

    /** Charged on wet bushels, with no minimum and no free points. */
    public static DryingSchedule of(String commodityCode, List<DryingTier> tiers) {
        return new DryingSchedule(commodityCode, tiers, DryingBasis.WET, new BigDecimal("0.00"), BigDecimal.ZERO);
    }

    public DryingSchedule withBasis(DryingBasis value) {
        return new DryingSchedule(commodityCode, tiers, value, minimumCharge, freePoints);
    }

    public DryingSchedule withMinimumCharge(BigDecimal value) {
        return new DryingSchedule(commodityCode, tiers, basis, value, freePoints);
    }

    public DryingSchedule withFreePoints(BigDecimal value) {
        return new DryingSchedule(commodityCode, tiers, basis, minimumCharge, value);
    }

    /** Blended dollars per bushel for this many points over base. */
    public BigDecimal rateFor(BigDecimal points) {
        BigDecimal chargeable = points.subtract(freePoints);
        if (chargeable.signum() <= 0) {
            return Money.ZERO_PRICE;
        }
        BigDecimal remaining = chargeable;
        BigDecimal consumed = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (DryingTier tier : tiers) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal band;
            if (tier.upToPoints().isEmpty()) {
                band = remaining;
            } else {
                band = Decimals.min(remaining, tier.upToPoints().get().subtract(consumed));
                if (band.signum() <= 0) {
                    continue;
                }
            }
            total = total.add(band.multiply(tier.ratePerPoint()));
            remaining = remaining.subtract(band);
            consumed = consumed.add(band);
        }
        return Units.quantize(total, Money.PRICE_PLACES);
    }

    /** Dollars owed for drying this load. */
    public BigDecimal charge(BigDecimal bushels, BigDecimal points) {
        BigDecimal gross = Money.extend(bushels, rateFor(points));
        if (gross.signum() == 0) {
            return Money.amount("0.00");
        }
        return Money.amount(Decimals.max(gross, minimumCharge));
    }
}
