package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dividing a load among the parties on its split. */
public final class Tickets {

    private Tickets() {}

    /**
     * Divide a bushel count across the shares, in split order.
     *
     * <p>Bushels are split at four decimals and the residue from rounding is given to the largest
     * share, which keeps the parts adding to the whole without ever giving a minority owner more
     * than the split says.
     */
    public static Map<String, BigDecimal> splitBushels(BigDecimal bushels, List<SplitShare> splits) {
        if (splits.isEmpty()) {
            throw new TicketError("cannot split across no shares");
        }
        BigDecimal whole = Units.quantize(bushels, Units.BUSHEL_PLACES);
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        BigDecimal allotted = BigDecimal.ZERO;
        for (SplitShare share : splits) {
            BigDecimal part = Units.percentOf(whole, share.percent(), Units.BUSHEL_PLACES);
            parts.put(share.partyId(), part);
            allotted = allotted.add(part);
        }
        BigDecimal residue = whole.subtract(allotted);
        if (residue.signum() != 0) {
            SplitShare biggest = splits.stream()
                    .max(Comparator.comparing(SplitShare::percent).thenComparing(SplitShare::partyId))
                    .orElseThrow();
            parts.put(
                    biggest.partyId(),
                    Units.quantize(parts.get(biggest.partyId()).add(residue), Units.BUSHEL_PLACES));
        }
        return parts;
    }
}
