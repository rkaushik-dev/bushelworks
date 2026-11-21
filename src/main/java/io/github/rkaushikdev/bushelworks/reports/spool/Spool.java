package io.github.rkaushikdev.bushelworks.reports.spool;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Quoting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A day's run of printed documents, and getting them to the people they belong to.
 *
 * <p>Three ways out of the office, and the house uses all of them in the same week:
 *
 * <ul>
 *   <li>the dot matrix in the scale house, which takes plain text and a form feed
 *   <li>the laser in the office, which takes the same text and wants page breaks at sixty six lines
 *   <li>a file per producer, which the office attaches to an email by hand
 * </ul>
 *
 * <p>All three are built from the same rendered text. Nothing here sends anything: it prepares the
 * text and the files and hands back their names, because a run that also sends cannot be tested and
 * cannot be run again after a paper jam.
 */
public final class Spool {

    /** What separates one document from the next on the printer. */
    public static final String FORM_FEED = "\f";

    /** The lines on a printed page. */
    public static final int PAGE_LINES = 66;

    /** The suffix a written file takes when nobody names another. */
    public static final String SUFFIX = ".txt";

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");

    private final LocalDate day;
    private final List<SpoolDocument> documents = new ArrayList<>();

    /** An empty run for {@code day}. */
    public Spool(LocalDate day) {
        this.day = Objects.requireNonNull(day, "day");
    }

    public LocalDate day() {
        return day;
    }

    /** The documents, in the order they were added. */
    public List<SpoolDocument> documents() {
        return Collections.unmodifiableList(documents);
    }

    /**
     * Put a document in the run.
     *
     * @throws SpoolError when a document of the same name is already in it
     */
    public void add(SpoolDocument document) {
        if (documents.stream().anyMatch(item -> item.name().equals(document.name()))) {
            throw new SpoolError(document.name() + " is already in the spool");
        }
        documents.add(document);
    }

    public int count() {
        return documents.size();
    }

    public int totalPages() {
        return documents.stream().mapToInt(SpoolDocument::pages).sum();
    }

    /** One producer's documents, in the order they were added. */
    public List<SpoolDocument> forParty(String partyCode) {
        return documents.stream()
                .filter(item -> item.partyCode().equals(partyCode))
                .toList();
    }

    /** Everybody with a document in the run, once each, in code order. */
    public List<String> parties() {
        return documents.stream()
                .map(SpoolDocument::partyCode)
                .distinct()
                .sorted()
                .toList();
    }

    /** A file name for a document, ending {@link #SUFFIX}. */
    public static String safeFilename(String name) {
        return safeFilename(name, SUFFIX);
    }

    /**
     * A name a file system will take, from a document number.
     *
     * <p>Statement numbers have hyphens and nothing else, but a producer code can have anything in it
     * that somebody typed at the window in 1998. Every run of characters other than ASCII letters,
     * digits, dots, underscores and hyphens becomes one hyphen, and hyphens at either end are dropped.
     *
     * @throws SpoolError when that leaves nothing
     */
    public static String safeFilename(String name, String suffix) {
        String cleaned =
                EDGE_HYPHENS.matcher(UNSAFE.matcher(name).replaceAll("-")).replaceAll("");
        if (cleaned.isEmpty()) {
            throw new SpoolError(Quoting.quoted(name) + " leaves nothing to name a file after");
        }
        return cleaned + suffix;
    }

    /**
     * One stream for the dot matrix, a form feed between documents.
     *
     * @throws SpoolError when there is nothing to print
     */
    public String toMatrix() {
        if (documents.isEmpty()) {
            throw new SpoolError("nothing to print");
        }
        return String.join(
                FORM_FEED, documents.stream().map(SpoolDocument::text).toList());
    }

    /**
     * The same stream for the laser, each document padded with empty lines to a whole number of pages
     * so the next one starts on a fresh page.
     *
     * @throws SpoolError when there is nothing to print
     */
    public String toPages() {
        if (documents.isEmpty()) {
            throw new SpoolError("nothing to print");
        }
        List<String> padded = new ArrayList<>();
        for (SpoolDocument document : documents) {
            int padding = Math.floorMod(-document.lines(), PAGE_LINES);
            padded.add(document.text() + "\n".repeat(padding));
        }
        return String.join(FORM_FEED, padded);
    }

    /**
     * One file per document, named after it, in UTF-8 under a directory named for the day, which is
     * made if it is not there. Writing the same run again overwrites the files rather than adding to
     * them.
     *
     * @return the files written, in document order
     * @throws SpoolError when there is nothing to write, or a document's name leaves nothing to name a
     *     file after
     * @throws IOException when the directory or a file cannot be written
     */
    public List<Path> writeFiles(Path directory) throws IOException {
        if (documents.isEmpty()) {
            throw new SpoolError("nothing to write");
        }
        Path target = directory.resolve(day.toString());
        Files.createDirectories(target);
        List<Path> written = new ArrayList<>();
        for (SpoolDocument document : documents) {
            Path path = target.resolve(safeFilename(document.name()));
            Files.writeString(path, document.text(), StandardCharsets.UTF_8);
            written.add(path);
        }
        return List.copyOf(written);
    }

    /** What went out, for the office to tick off against the envelopes, in document name order. */
    public String manifest() {
        List<String> lines = new ArrayList<>();
        lines.add("SPOOL " + day);
        lines.add(count() + " documents, " + totalPages() + " pages");
        lines.add("-".repeat(60));
        documents.stream()
                .sorted(Comparator.comparing(SpoolDocument::name))
                .forEach(document -> lines.add(left(document.name(), 24)
                        + left(document.partyCode(), 20)
                        + right(String.valueOf(document.pages()), 4)
                        + " pages"));
        return String.join("\n", lines);
    }
}
