package io.github.rkaushikdev.bushelworks.services.board;

import java.util.Comparator;
import java.util.Objects;

/**
 * What a bid is for: a commodity, a delivery period and a site.
 *
 * @param commodityCode the commodity bid for
 * @param period the delivery period, such as {@code harvest}
 * @param locationCode the site, or {@code *} for every site
 */
public record Slot(String commodityCode, String period, String locationCode) {

    /** Commodity, then period, then site. */
    public static final Comparator<Slot> ORDER = Comparator.comparing(Slot::commodityCode)
            .thenComparing(Slot::period)
            .thenComparing(Slot::locationCode);

    public Slot {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(locationCode, "locationCode");
        if (commodityCode.isEmpty()) {
            throw new BoardError("a slot needs a commodity");
        }
        if (period.isEmpty()) {
            throw new BoardError("a slot needs a delivery period");
        }
    }

    /** A slot good at every site. */
    public Slot(String commodityCode, String period) {
        this(commodityCode, period, "*");
    }

    /** {@code CORN harvest HOME}. */
    public String label() {
        return commodityCode + " " + period + " " + locationCode;
    }
}
