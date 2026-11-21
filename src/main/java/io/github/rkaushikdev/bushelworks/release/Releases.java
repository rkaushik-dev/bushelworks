package io.github.rkaushikdev.bushelworks.release;

import io.github.rkaushikdev.bushelworks.Bushelworks;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What changed, release by release, as data rather than prose.
 *
 * <p>A changelog in a text file is a changelog that goes stale, because nothing reads it. This one is
 * read: the version is checked against the build, the entries are checked for order, and the command
 * line prints it. A release that is not in here is a release the tests refuse.
 *
 * <p>The audience is the house, not the internet. Entries say what the office will notice, and the
 * ones that matter most are the ones marked breaking, because those are the ones somebody has to be
 * telephoned about before the box is upgraded on a Sunday night.
 */
public final class Releases {

    /** Every release, oldest first. */
    public static final List<Release> HISTORY = List.of(
            new Release(
                    "0.1.0",
                    LocalDate.of(2024, 6, 24),
                    "Weights, grade factors and the shrink table.",
                    List.of(
                            "test weight, moisture and damage read off a probe sample",
                            "shrink and dockage worked out from the schedule in force on the day",
                            "the reference and ticket tables"),
                    List.of(),
                    List.of("V1")),
            new Release(
                    "0.2.0",
                    LocalDate.of(2024, 8, 2),
                    "Contracts, settlement and the printed statement.",
                    List.of(
                            "forward, basis and hedged to arrive contracts",
                            "settlement lines in a fixed order with a balance check",
                            "the eighty column statement the dot matrix prints"),
                    List.of(),
                    List.of("V2")),
            new Release(
                    "0.3.0",
                    LocalDate.of(2024, 9, 30),
                    "Storage, warehouse receipts and the storage accrual.",
                    List.of(
                            "open lots, withdrawals and negotiable receipts",
                            "storage charged on the bushels open during the billing period",
                            "the daily position, and the sign of deferred price bushels"),
                    List.of(),
                    List.of("V3", "V4")),
            new Release(
                    "0.4.0",
                    LocalDate.of(2024, 11, 29),
                    "Sales, shipments and the bin yard.",
                    List.of(
                            "sale contracts, loadings and rejected cars",
                            "bins with a running blend and an aeration log",
                            "the HTTP interface, with a key per role"),
                    List.of(),
                    List.of("V5", "V6")),
            new Release(
                    "0.5.0",
                    LocalDate.of(2025, 2, 14),
                    "The office tools: audit trail, day end and the exports.",
                    List.of(
                            "an audit entry for every document that moves",
                            "the day end close and its sheet",
                            "cheque runs, ACH files and the year end summary"),
                    List.of(),
                    List.of()),
            new Release(
                    "0.6.0",
                    LocalDate.of(2025, 3, 21),
                    "The complaint register and the hedge desk.",
                    List.of(
                            "complaints, worked and closed through the domain rules",
                            "the morning hedge sheet, position against the broker statement",
                            "the patronage table and the board reports"),
                    List.of(),
                    List.of("V7")),
            new Release(
                    "0.7.0",
                    LocalDate.of(2025, 4, 28),
                    "The migration off the old system, and the exports it needs.",
                    List.of(
                            "the old fixed width ticket file reads and loads, dry run by default",
                            "CSV exports for the auditor, read back before they are called done",
                            "reporting indexes, and one group by instead of a query per contract"),
                    List.of("settlement line codes now print eleven characters, not twelve, so a"
                            + " script that slices the statement by column has to move"),
                    List.of("V8")),
            new Release(
                    "0.8.0",
                    LocalDate.of(2025, 5, 26),
                    "The general ledger, and the periods that decide what can post.",
                    List.of(
                            "a chart of accounts, where an account with sub accounts is a"
                                    + " heading and will not take a posting itself",
                            "an append only journal, corrected by posting a reverse rather than"
                                    + " by editing anything",
                            "accounting periods, so a ticket keyed after the return went in"
                                    + " lands in the month that is still open",
                            "the day close posts the statements it counted and reads the"
                                    + " books back, instead of adding the lines up a second time",
                            "trial balance, income statement and balance sheet, over http and" + " on the command line",
                            "the year end close, which empties revenue and expense into equity"
                                    + " and can send part of it to a patronage account"),
                    List.of(),
                    List.of("V9")));

    private Releases() {}

    /** The release this build is. */
    public static Release current() {
        return current(Bushelworks.VERSION);
    }

    /**
     * The release a build numbered {@code version} is.
     *
     * @throws IllegalArgumentException when the history has no such release, which is a version bump
     *     nobody described
     */
    public static Release current(String version) {
        return HISTORY.stream()
                .filter(entry -> entry.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(version + " is not in the release history"));
    }

    /** The newest release in the history. */
    public static Release latest() {
        return HISTORY.get(HISTORY.size() - 1);
    }

    /**
     * Everything released after a version, oldest first.
     *
     * <p>What the house reads before an upgrade. A box three releases behind gets three entries and one
     * of them is marked breaking. The version is compared number by number, so one nobody shipped,
     * such as {@code 0.5.5}, still answers, and {@code 0.7} counts as coming before {@code 0.7.0}.
     *
     * @throws NumberFormatException when a part of the version is not a whole number
     */
    public static List<Release> since(String version) {
        int[] after = numbersOf(version);
        return HISTORY.stream()
                .filter(entry -> Arrays.compare(numbersOf(entry.version()), after) > 0)
                .toList();
    }

    /** Only the lines somebody has to be telephoned about, each led by its version. */
    public static List<String> breakingSince(String version) {
        List<String> lines = new ArrayList<>();
        for (Release entry : since(version)) {
            entry.breaking().forEach(note -> lines.add(entry.version() + ": " + note));
        }
        return List.copyOf(lines);
    }

    /** The schema migrations an upgrade from a version will run, in the order they run. */
    public static List<String> migrationsSince(String version) {
        List<String> names = new ArrayList<>();
        since(version).forEach(entry -> names.addAll(entry.migrations()));
        return List.copyOf(names);
    }

    /** One release as a plain block, for the terminal. */
    public static String render(Release entry) {
        List<String> lines = new ArrayList<>();
        lines.add(entry.version() + "  " + entry.releasedOn());
        lines.add("  " + entry.summary());
        entry.changes().forEach(change -> lines.add("  - " + change));
        entry.breaking().forEach(note -> lines.add("  ! " + note));
        if (entry.needsAMigration()) {
            lines.add("  migrations: " + String.join(", ", entry.migrations()));
        }
        return String.join("\n", lines);
    }

    /** The whole history, newest first, which is how it gets read. */
    public static String renderHistory() {
        return renderHistory(HISTORY);
    }

    /** Some of the history, newest first. */
    public static String renderHistory(List<Release> entries) {
        List<String> blocks = new ArrayList<>();
        for (int index = entries.size() - 1; index >= 0; index--) {
            blocks.add(render(entries.get(index)));
        }
        return String.join("\n\n", blocks);
    }

    private static int[] numbersOf(String version) {
        return Arrays.stream(version.split("\\.", -1))
                .mapToInt(part -> Integer.parseInt(part.strip()))
                .toArray();
    }
}
