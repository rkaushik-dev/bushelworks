package io.github.rkaushikdev.bushelworks.services.legacy;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Writing what the old file gave up into the ticket file.
 *
 * <p>Reading a file and writing it are two different failures, and {@link LegacyReader} does only
 * the first. A row that parsed and then would not go in is a row somebody has to look at by hand, and
 * it is a different pile from the ones that would not parse at all.
 *
 * <p>Nothing here commits. The caller decides, and the migration is run once with the commit turned
 * off to see what it would do.
 */
public final class LegacyLoader {

    private static final BigDecimal WHOLE_LOAD = new BigDecimal("100");

    private LegacyLoader() {}

    /** Turn an old row into a ticket, its sample exactly what the old file had. */
    public static ScaleTicket toTicket(LegacyRow row) {
        return toTicket(row, Map.of());
    }

    /**
     * Turn an old row into a ticket the rest of the system understands.
     *
     * <p>The old file has no split table. Every ticket comes in whole to the party on it, and a
     * landlord share that existed in the old system is lost, which the house knows and accepted: those
     * settlements are eleven years closed.
     *
     * <p>A blank moisture is left out of the sample rather than sent in as zero. Zero moisture grades
     * as bone dry and pays a shrink of nothing, and the old system meant it as "we did not test this
     * load".
     *
     * @param assume readings for factors the old system never had a column for at all. There is no
     *     BCFM in that file and there never was, so a historic ticket cannot be graded without one. The
     *     house supplies its long run average once for the whole migration rather than the loader
     *     inventing a number per row, and the remark on every imported ticket says the sample was
     *     filled in. A reading the file does have wins over an assumed one.
     */
    public static ScaleTicket toTicket(LegacyRow row, Map<Factor, BigDecimal> assume) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>(assume);
        row.moisture().ifPresent(value -> sample.put(Factor.MOISTURE, value));
        row.testWeight().ifPresent(value -> sample.put(Factor.TEST_WEIGHT, value));
        row.damage().ifPresent(value -> sample.put(Factor.DAMAGE_TOTAL, value));
        String note = "imported from the old file, line " + row.lineNumber();
        if (!assume.isEmpty()) {
            String filled = assume.keySet().stream().map(Factor::value).sorted().collect(Collectors.joining(", "));
            note = note + "; " + filled + " assumed";
        }
        return ScaleTicket.builder()
                .ticketNumber(row.ticketNumber())
                .scaleDate(row.scaleDate())
                .locationCode(row.locationCode())
                .commodityCode(row.commodityCode())
                .grossPounds(row.grossPounds())
                .tarePounds(row.tarePounds())
                .disposition(row.disposition())
                .splits(List.of(new SplitShare(row.partyCode(), WHOLE_LOAD)))
                .sample(sample)
                .remarks(note)
                .voided(row.voided())
                .build();
    }

    /** Write parsed rows into the ticket file, skipping tickets already on file, nothing assumed. */
    public static Loaded load(UnitOfWork uow, ParsedFile parsed, RuleBook rules) {
        return load(uow, parsed, rules, true, Map.of());
    }

    /**
     * Write parsed rows into the ticket file, keeping what would not go.
     *
     * <p>A ticket already on file is skipped rather than refused when {@code skipExisting} is set. The
     * migration gets run more than once, usually because it stopped halfway the first time, and a
     * second pass that falls over on the rows the first one wrote is useless.
     *
     * <p>Voided rows are always skipped, and there is no option to bring them in. Intake refuses a
     * voided ticket outright, which is right: a void is a load that did not happen, and grading one
     * would put a discount and a shrink on file against grain nobody ever took. The old file keeps the
     * void row; this database does not need a second copy of it.
     *
     * <p>A row the rules refuse is skipped with the refusal as its reason, and the rest of the file
     * still goes in.
     *
     * @param assume readings for factors the old file has no column for, as {@link #toTicket(LegacyRow,
     *     Map)} takes them
     */
    public static Loaded load(
            UnitOfWork uow, ParsedFile parsed, RuleBook rules, boolean skipExisting, Map<Factor, BigDecimal> assume) {
        List<String> written = new ArrayList<>();
        List<Rejected> skipped = new ArrayList<>();
        for (LegacyRow row : parsed.rows()) {
            if (row.voided()) {
                skipped.add(new Rejected(row.lineNumber(), row.ticketNumber(), "voided in the old system"));
                continue;
            }
            if (skipExisting && uow.tickets().exists(row.locationCode(), row.ticketNumber())) {
                skipped.add(new Rejected(row.lineNumber(), row.ticketNumber(), "already on file"));
                continue;
            }
            try {
                ScaleHouse.recordLoad(uow, toTicket(row, assume), row.partyCode(), rules);
            } catch (BushelworksError refused) {
                skipped.add(new Rejected(row.lineNumber(), row.ticketNumber(), refused.getMessage()));
                continue;
            }
            written.add(row.ticketNumber());
        }
        return new Loaded(written, skipped);
    }

    /** One paragraph for the migration log, naming up to five of the reasons rows were skipped. */
    public static String loadSummary(Loaded loaded) {
        if (loaded.isClean()) {
            return loaded.count() + " tickets written, none skipped";
        }
        TreeSet<String> reasons =
                loaded.skipped().stream().map(Rejected::reason).collect(Collectors.toCollection(TreeSet::new));
        String named = String.join(", ", reasons.stream().limit(5).toList());
        return loaded.count() + " tickets written, " + loaded.skipped().size() + " skipped: " + named;
    }
}
