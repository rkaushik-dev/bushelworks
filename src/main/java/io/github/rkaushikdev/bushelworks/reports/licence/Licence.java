package io.github.rkaushikdev.bushelworks.reports.licence;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The warehouse licence return.
 *
 * <p>Monthly, to the state. It says how much grain the house is holding for other people, how much
 * it owns, and whether the two together fit inside the licensed capacity. The last one is the
 * question with teeth: a licensed warehouse over capacity is a licence problem, not a logistics one.
 */
public final class Licence {

    /** The return is this many characters wide. */
    public static final int WIDTH = 72;

    /** The state wants a note when a site is inside this fraction of its licence. */
    public static final BigDecimal TIGHT_MARGIN = new BigDecimal("0.05");

    /** What a return rolled up from every site is stamped with. */
    public static final String EVERY_SITE = "*";

    private Licence() {}

    /** The return under the default house name. */
    public static String render(LicenceReturn one) {
        return render(one, Settings.DEFAULT_HOUSE_NAME);
    }

    /**
     * The return as it goes in the envelope: the obligation by its parts, then what is held against
     * the licence, with a note when the site is over its licence or tight against it. Bushels print
     * at the places they were kept.
     */
    public static String render(LicenceReturn one, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("WAREHOUSE RETURN " + one.asOf(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add("Site          " + one.locationCode());
        lines.add("Licence       " + one.licenceNumber());
        lines.add("-".repeat(WIDTH));
        lines.add(figure("storage obligation", one.storageObligation()));
        lines.add(figure("deferred price", one.deferredPrice()));
        lines.add(figure("receipts outstanding", one.receiptsOutstanding()));
        lines.add(figure("total obligation", one.obligation()));
        lines.add("-".repeat(WIDTH));
        lines.add(figure("house owned", one.houseOwned()));
        lines.add(figure("total held", one.totalHeld()));
        lines.add(figure("licensed capacity", one.licensedCapacity()));
        lines.add(figure("unused", one.unusedCapacity()));
        lines.add(left("used", 32) + right(one.percentUsed().toString(), 19) + "%");
        if (one.isOverCapacity()) {
            lines.add("");
            lines.add("OVER LICENSED CAPACITY. This has to be explained.");
        } else if (one.isTight()) {
            lines.add("");
            lines.add("Within five percent of the licence.");
        }
        return String.join("\n", lines);
    }

    /**
     * Roll several sites' returns for one month into one house wide return stamped {@link
     * #EVERY_SITE}, listing every licence in order.
     *
     * @throws LicenceError when there is nothing to combine, or a return is for another month
     */
    public static LicenceReturn combine(List<LicenceReturn> returns, LocalDate asOf) {
        if (returns.isEmpty()) {
            throw new LicenceError("nothing to combine");
        }
        if (returns.stream().anyMatch(one -> !one.asOf().equals(asOf))) {
            throw new LicenceError("cannot combine returns from different months");
        }
        return new LicenceReturn(
                EVERY_SITE,
                String.join(
                        ", ",
                        returns.stream()
                                .map(LicenceReturn::licenceNumber)
                                .sorted()
                                .toList()),
                asOf,
                sum(returns, LicenceReturn::licensedCapacity),
                sum(returns, LicenceReturn::storageObligation),
                sum(returns, LicenceReturn::deferredPrice),
                sum(returns, LicenceReturn::receiptsOutstanding),
                sum(returns, LicenceReturn::houseOwned));
    }

    /** The returns holding more than their licence allows, in the order given. */
    public static List<LicenceReturn> overCapacity(List<LicenceReturn> returns) {
        return returns.stream().filter(LicenceReturn::isOverCapacity).toList();
    }

    private static String figure(String label, BigDecimal value) {
        return left(label, 32) + right(value.toString(), 20);
    }

    private static BigDecimal sum(List<LicenceReturn> returns, Function<LicenceReturn, BigDecimal> part) {
        BigDecimal total = BigDecimal.ZERO;
        for (LicenceReturn one : returns) {
            total = total.add(part.apply(one));
        }
        return total;
    }
}
