package io.github.rkaushikdev.bushelworks.reports;

import static io.github.rkaushikdev.bushelworks.reports.Columns.centred;
import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The fixed width text every printed form is laid out with. */
class ColumnsTest {

    @Test
    void aFieldIsPaddedToItsColumnAndNeverCut() {
        assertThat(left("LOC", 8)).isEqualTo("LOC     ");
        assertThat(right("NET", 6)).isEqualTo("   NET");
        assertThat(left("HOMESTEADSITE", 8)).isEqualTo("HOMESTEADSITE");
        assertThat(right("1,340,932.89", 4)).isEqualTo("1,340,932.89");
    }

    @Test
    void clippingKeepsTheFirstCharacters() {
        assertThat(clip("ADV-2024-0912", 11)).isEqualTo("ADV-2024-09");
        assertThat(clip("DRY", 11)).isEqualTo("DRY");
        assertThat(clip("DRY", 0)).isEmpty();
    }

    @Test
    void widthsCountCharactersNotCodeUnits() {
        String name = "é😀x";
        assertThat(clip(name, 2)).isEqualTo("é😀");
        assertThat(left(name, 4)).isEqualTo(name + " ");
        assertThat(centred(name, 7)).isEqualTo("  " + name);
    }

    @Test
    void anOddMarginPutsTheExtraSpaceLeftOnlyOnAnOddWidth() {
        assertThat(centred("ab", 5)).isEqualTo("  ab");
        assertThat(centred("abc", 6)).isEqualTo(" abc");
        assertThat(centred("a", 4)).isEqualTo(" a");
    }

    @Test
    void textWiderThanTheFormIsPrintedAsItIs() {
        assertThat(centred("abcd", 3)).isEqualTo("abcd");
        assertThat(centred("abcd  ", 3)).isEqualTo("abcd");
    }
}
