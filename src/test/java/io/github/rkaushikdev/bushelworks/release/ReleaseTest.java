package io.github.rkaushikdev.bushelworks.release;

import static io.github.rkaushikdev.bushelworks.release.Releases.HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Bushelworks;
import io.github.rkaushikdev.bushelworks.cli.Shell;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The release history, kept honest by the tests that read it. */
class ReleaseTest {

    /** The migration versions shipped as scripts, as the history names them: {@code V1} and so on. */
    static Set<String> migrationsOnDisk() throws IOException, URISyntaxException {
        Path scripts = Path.of(Releases.class.getResource("/db/migration").toURI());
        try (Stream<Path> files = Files.list(scripts)) {
            Set<String> versions = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.contains("__"))
                    .map(name -> name.substring(0, name.indexOf("__")))
                    .collect(Collectors.toSet());
            assertThat(versions).as("the migration scripts were found").isNotEmpty();
            return versions;
        }
    }

    static Set<String> migrationsNamed() {
        return HISTORY.stream().flatMap(entry -> entry.migrations().stream()).collect(Collectors.toSet());
    }

    static Shell.Result changes(String... args) {
        String[] line = Stream.concat(Stream.of("changes"), Stream.of(args)).toArray(String[]::new);
        return Shell.bare().run(line);
    }

    @Test
    void thisBuildIsInTheHistory() {
        assertThat(Releases.current().version()).isEqualTo(Bushelworks.VERSION);
    }

    /**
     * A release added below the current version means somebody forgot to bump.
     *
     * <p>It is a cheap check and it has already caught the reverse: a version bump with no entry,
     * which reads as a release nobody can describe.
     */
    @Test
    void thisBuildIsTheNewestEntry() {
        assertThat(Releases.latest().version()).isEqualTo(Bushelworks.VERSION);
    }

    @Test
    void theVersionsGoUp() {
        assertThat(HISTORY).isSortedAccordingTo(Comparator.comparing(Release::numbers));
    }

    @Test
    void noVersionAppearsTwice() {
        List<String> versions = HISTORY.stream().map(Release::version).toList();
        assertThat(new HashSet<>(versions)).hasSameSizeAs(versions);
    }

    @Test
    void theDatesGoUp() {
        assertThat(HISTORY).isSortedAccordingTo(Comparator.comparing(Release::releasedOn));
    }

    @Test
    void everyVersionIsThreeNumbers() {
        assertThat(HISTORY).allSatisfy(entry -> assertThat(entry.version()).matches("\\d+\\.\\d+\\.\\d+"));
    }

    @Test
    void everyReleaseSaysSomething() {
        assertThat(HISTORY).allSatisfy(entry -> assertThat(entry.summary()).isNotEmpty());
    }

    @Test
    void everyReleaseListsWhatChanged() {
        List<String> empty = HISTORY.stream()
                .filter(entry -> entry.changes().isEmpty())
                .map(Release::version)
                .toList();
        assertThat(empty).isEmpty();
    }

    @Test
    void aReleaseWithNoBreakingNotesIsNotBreaking() {
        assertThat(HISTORY.get(0).isBreaking()).isFalse();
    }

    @Test
    void aReleaseWithABreakingNoteSaysSo() {
        List<Release> breaking =
                HISTORY.stream().filter(entry -> !entry.breaking().isEmpty()).toList();
        assertThat(breaking).isNotEmpty().allMatch(Release::isBreaking);
    }

    @Test
    void aReleaseThatNamesAMigrationNeedsOne() {
        List<Release> withMigrations =
                HISTORY.stream().filter(entry -> !entry.migrations().isEmpty()).toList();
        assertThat(withMigrations).isNotEmpty().allMatch(Release::needsAMigration);
    }

    /**
     * A migration in the history that does not exist is worse than none.
     *
     * <p>The upgrade note tells the house how many migrations a jump will run, and a number that
     * counts a migration nobody wrote is a number that makes the house plan for a longer outage than
     * it needs.
     */
    @Test
    void everyNamedMigrationIsAFileOnDisk() throws Exception {
        Set<String> onDisk = migrationsOnDisk();
        assertThat(onDisk).containsAll(migrationsNamed());
    }

    @Test
    void everyMigrationOnDiskIsNamedByARelease() throws Exception {
        assertThat(migrationsNamed()).containsAll(migrationsOnDisk());
    }

    @Test
    void nothingWasReleasedAfterTheNewest() {
        assertThat(Releases.since(Releases.latest().version())).isEmpty();
    }

    @Test
    void everythingComesBackFromTheBeginning() {
        assertThat(Releases.since("0.0.0")).isEqualTo(HISTORY);
    }

    @Test
    void aJumpOfTwoReturnsTwo() {
        assertThat(Releases.since(HISTORY.get(HISTORY.size() - 3).version())).hasSize(2);
    }

    @Test
    void aVersionNobodyShippedStillAnswers() {
        assertThat(Releases.since("0.5.5")).hasSize(3);
    }

    @Test
    void theBreakingNotesCarryTheirVersion() {
        List<String> lines = Releases.breakingSince("0.0.0");
        assertThat(lines).isNotEmpty().allMatch(line -> line.matches("\\d+\\.\\d+\\.\\d+: .*"));
    }

    @Test
    void anUpgradeWithNothingBreakingSaysNothing() {
        assertThat(Releases.breakingSince(Releases.latest().version())).isEmpty();
    }

    @Test
    void theMigrationsForAJumpAddUp() {
        List<String> migrations = Releases.migrationsSince("0.0.0");
        int named =
                HISTORY.stream().mapToInt(entry -> entry.migrations().size()).sum();
        assertThat(migrations).hasSize(named);
    }

    @Test
    void aJumpFromTheNewestRunsNoMigrations() {
        assertThat(Releases.migrationsSince(Releases.latest().version())).isEmpty();
    }

    @Test
    void aReleaseThatIsNotInTheHistoryIsRefused() {
        assertThatThrownBy(() -> Releases.current("9.9.9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9.9.9");
    }

    @Test
    void oneReleaseRendersAsABlock() {
        String text = Releases.render(Releases.latest());
        assertThat(text)
                .startsWith(Releases.latest().version())
                .contains(Releases.latest().summary());
    }

    @Test
    void aBreakingNoteIsMarkedInTheBlock() {
        Release loud = HISTORY.stream()
                .filter(entry -> !entry.breaking().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(Releases.render(loud).split("\n", -1))
                .anyMatch(line -> line.strip().startsWith("!"));
    }

    @Test
    void theMigrationsAreNamedInTheBlock() {
        assertThat(Releases.render(Releases.latest())).contains("migrations:");
    }

    @Test
    void aReleaseWithNoMigrationsDoesNotMentionThem() {
        Release quiet = HISTORY.stream()
                .filter(entry -> entry.migrations().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(Releases.render(quiet)).doesNotContain("migrations:");
    }

    @Test
    void theHistoryPrintsNewestFirst() {
        String text = Releases.renderHistory();
        assertThat(text.indexOf(HISTORY.get(HISTORY.size() - 1).version()))
                .isLessThan(text.indexOf(HISTORY.get(0).version()));
    }

    @Test
    void theCommandPrintsTheWholeHistory() {
        Shell.Result result = changes();
        assertThat(result.code()).isZero();
        assertThat(HISTORY).allSatisfy(entry -> assertThat(result.output()).contains(entry.version()));
    }

    @Test
    void theCommandCanBeAskedForAJump() {
        Shell.Result result = changes("--since", HISTORY.get(HISTORY.size() - 2).version());
        assertThat(result.output())
                .contains(HISTORY.get(HISTORY.size() - 1).version())
                .doesNotContain(HISTORY.get(0).version());
    }

    @Test
    void theCommandCountsTheMigrationsAJumpRuns() {
        assertThat(changes("--since", "0.0.0").output()).contains("this upgrade runs 9 migrations");
    }

    @Test
    void aJumpFromTheNewestSaysThereIsNothing() {
        assertThat(changes("--since", Releases.latest().version()).output()).contains("nothing since then");
    }

    @Test
    void theCommandCanBeAskedForOnlyTheBreakingLines() {
        Shell.Result result = changes("--since", "0.0.0", "--breaking");
        assertThat(result.output())
                .contains("settlement line codes")
                .doesNotContain(HISTORY.get(0).summary());
    }

    @Test
    void aCleanUpgradeSaysNothingIsBreaking() {
        assertThat(changes("--since", Releases.latest().version(), "--breaking").output())
                .contains("nothing breaking");
    }
}
