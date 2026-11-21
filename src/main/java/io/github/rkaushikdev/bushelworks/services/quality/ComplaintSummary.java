package io.github.rkaushikdev.bushelworks.services.quality;

import io.github.rkaushikdev.bushelworks.Ordered;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The complaints page the manager looks at once a month.
 *
 * @param opens the first day of the span
 * @param closes the last day of the span
 * @param count every complaint raised in the span
 * @param open how many of them are still open
 * @param late how many arrived outside the window the house normally entertains
 * @param upheldRate of those closed, the percent upheld, to a tenth
 * @param credits what the house gave back on them
 * @param bySource how many came from each kind of caller, by the caller's value
 * @param indefensible the open ones with no sample left to argue from, oldest first
 * @param repeatBins the bins complained about three times or more
 * @param windowDays how many days after a delivery a complaint is normally entertained
 */
public record ComplaintSummary(
        LocalDate opens,
        LocalDate closes,
        int count,
        int open,
        int late,
        BigDecimal upheldRate,
        BigDecimal credits,
        Map<String, Integer> bySource,
        List<String> indefensible,
        List<String> repeatBins,
        int windowDays) {

    public ComplaintSummary {
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        Objects.requireNonNull(upheldRate, "upheldRate");
        Objects.requireNonNull(credits, "credits");
        bySource = Ordered.copyOf(bySource);
        indefensible = List.copyOf(indefensible);
        repeatBins = List.copyOf(repeatBins);
    }
}
