package io.github.rkaushikdev.bushelworks.reports.spool;

import static io.github.rkaushikdev.bushelworks.reports.spool.Spool.FORM_FEED;
import static io.github.rkaushikdev.bushelworks.reports.spool.Spool.PAGE_LINES;
import static io.github.rkaushikdev.bushelworks.reports.spool.Spool.safeFilename;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolTest {

    static final LocalDate DAY = LocalDate.of(2024, 10, 20);

    @TempDir
    Path directory;

    static SpoolDocument aDocument() {
        return aDocument("S-2024-0417", "MERRICK", 20);
    }

    static SpoolDocument aDocument(int lines) {
        return aDocument("S-2024-0417", "MERRICK", lines);
    }

    static SpoolDocument aDocument(String name, String party, int lines) {
        String text =
                IntStream.range(0, lines).mapToObj(index -> "line " + index).collect(Collectors.joining("\n"));
        return new SpoolDocument(name, party, text);
    }

    static Spool aSpool() {
        Spool spool = new Spool(DAY);
        spool.add(aDocument());
        spool.add(aDocument("S-2024-0418", "HOLLIS", 20));
        return spool;
    }

    static int countOf(String text, String part) {
        return text.split(part, -1).length - 1;
    }

    @Test
    void aDocumentNeedsANameAndSomethingToPrint() {
        assertThatThrownBy(() -> new SpoolDocument("", "MERRICK", "anything"))
                .isInstanceOf(SpoolError.class)
                .hasMessage("a document needs a name");
        assertThatThrownBy(() -> new SpoolDocument("S-1", "MERRICK", "   \n  "))
                .isInstanceOf(SpoolError.class)
                .hasMessage("S-1: nothing to print");
    }

    @Test
    void aDocumentCountsItsLinesAndPages() {
        SpoolDocument shortOne = aDocument(20);
        assertThat(shortOne.lines()).isEqualTo(20);
        assertThat(shortOne.pages()).isEqualTo(1);
    }

    @Test
    void aLongDocumentRunsToMoreThanOnePage() {
        assertThat(aDocument(PAGE_LINES + 5).pages()).isEqualTo(2);
        assertThat(aDocument(PAGE_LINES).pages()).isEqualTo(1);
    }

    @Test
    void theSpoolCountsWhatIsInIt() {
        Spool spool = aSpool();
        assertThat(spool.count()).isEqualTo(2);
        assertThat(spool.totalPages()).isEqualTo(2);
    }

    @Test
    void theSameDocumentTwiceIsRefused() {
        Spool spool = aSpool();
        assertThatThrownBy(() -> spool.add(aDocument()))
                .isInstanceOf(SpoolError.class)
                .hasMessage("S-2024-0417 is already in the spool");
    }

    @Test
    void documentsCanBePulledOutByProducer() {
        Spool spool = aSpool();
        assertThat(spool.forParty("HOLLIS")).extracting(SpoolDocument::name).containsExactly("S-2024-0418");
        assertThat(spool.parties()).containsExactly("HOLLIS", "MERRICK");
    }

    @Test
    void theMatrixStreamSeparatesWithAFormFeed() {
        String text = aSpool().toMatrix();
        assertThat(countOf(text, FORM_FEED)).isEqualTo(1);
    }

    @Test
    void anEmptySpoolCannotBePrinted() {
        assertThatThrownBy(() -> new Spool(DAY).toMatrix())
                .isInstanceOf(SpoolError.class)
                .hasMessage("nothing to print");
        assertThatThrownBy(() -> new Spool(DAY).toPages())
                .isInstanceOf(SpoolError.class)
                .hasMessage("nothing to print");
    }

    @Test
    void thePageStreamPadsEachDocumentToAFullPage() {
        String first = aSpool().toPages().split(FORM_FEED, -1)[0];
        assertThat(first.split("\n", -1)).hasSize(PAGE_LINES);
    }

    @Test
    void aLongDocumentPadsToTheNextWholePage() {
        Spool spool = new Spool(DAY);
        spool.add(aDocument(PAGE_LINES + 5));
        String first = spool.toPages().split(FORM_FEED, -1)[0];
        assertThat(first.split("\n", -1)).hasSize(PAGE_LINES * 2);
    }

    @Test
    void thePageStreamIsTheTextAndItsPadding() {
        Spool spool = new Spool(DAY);
        spool.add(new SpoolDocument("S-2024-0418", "HOLLIS", "a\nb"));
        spool.add(aDocument(70));
        String pages = spool.toPages();
        assertThat(pages).startsWith("a\nb" + "\n".repeat(64) + FORM_FEED + "line 0\n");
        assertThat(pages).hasSize(679);
        assertThat(spool.totalPages()).isEqualTo(3);
    }

    @Test
    void aPlainNameMakesAPlainFilename() {
        assertThat(safeFilename("S-2024-0417")).isEqualTo("S-2024-0417.txt");
    }

    @Test
    void anAwkwardNameIsCleaned() {
        assertThat(safeFilename("S 2024/0417")).isEqualTo("S-2024-0417.txt");
        assertThat(safeFilename("--a'b//")).isEqualTo("a-b.txt");
    }

    @Test
    void aNameWithNothingUsableIsRefused() {
        assertThatThrownBy(() -> safeFilename("///"))
                .isInstanceOf(SpoolError.class)
                .hasMessage("'///' leaves nothing to name a file after");
        assertThatThrownBy(() -> safeFilename("\t"))
                .isInstanceOf(SpoolError.class)
                .hasMessage("'\\t' leaves nothing to name a file after");
    }

    @Test
    void theSuffixCanBeChanged() {
        assertThat(safeFilename("S-1", ".prn")).isEqualTo("S-1.prn");
    }

    @Test
    void writingFilesPutsOnePerDocumentUnderTheDay() throws IOException {
        List<Path> written = aSpool().writeFiles(directory);
        assertThat(written).hasSize(2);
        assertThat(written)
                .allSatisfy(path ->
                        assertThat(path.getParent().getFileName().toString()).isEqualTo("2024-10-20"));
    }

    @Test
    void theWrittenFileHoldsTheDocument() throws IOException {
        List<Path> written = aSpool().writeFiles(directory);
        assertThat(written.get(0).getFileName().toString()).isEqualTo("S-2024-0417.txt");
        assertThat(Files.readString(written.get(0), StandardCharsets.UTF_8))
                .contains("line 0")
                .isEqualTo(aDocument().text());
    }

    @Test
    void writingAnEmptySpoolIsRefused() {
        assertThatThrownBy(() -> new Spool(DAY).writeFiles(directory))
                .isInstanceOf(SpoolError.class)
                .hasMessage("nothing to write");
    }

    @Test
    void writingTwiceOverwritesRatherThanDuplicating() throws IOException {
        aSpool().writeFiles(directory);
        List<Path> written = aSpool().writeFiles(directory);
        try (Stream<Path> files = Files.list(directory.resolve("2024-10-20"))) {
            assertThat(files.count()).isEqualTo(written.size());
        }
    }

    @Test
    void theManifestListsWhatWentOut() {
        String text = aSpool().manifest();
        assertThat(text)
                .contains("SPOOL 2024-10-20")
                .contains("2 documents")
                .contains("S-2024-0417")
                .contains("HOLLIS");
    }

    @Test
    void theManifestIsInDocumentOrder() {
        assertThat(aSpool().manifest().lines().filter(line -> line.startsWith("S-")))
                .isSorted()
                .hasSize(2);
        Spool spool = new Spool(DAY);
        spool.add(new SpoolDocument("S-2024-0418", "HOLLIS", "a\nb"));
        spool.add(aDocument(70));
        String text = spool.manifest();
        List<String> rows = text.lines().filter(line -> line.startsWith("S-")).toList();
        assertThat(rows).isSorted().hasSize(2);
        assertThat(text)
                .isEqualTo(String.join(
                        "\n",
                        "SPOOL 2024-10-20",
                        "2 documents, 3 pages",
                        "-".repeat(60),
                        "S-2024-0417             MERRICK                2 pages",
                        "S-2024-0418             HOLLIS                 1 pages"));
    }

    @Test
    void anEmptySpoolStillHasAManifest() {
        assertThat(new Spool(DAY).manifest()).contains("0 documents");
    }
}
