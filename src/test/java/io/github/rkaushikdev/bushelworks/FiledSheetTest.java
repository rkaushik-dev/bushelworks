package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeTable;
import org.junit.jupiter.api.Test;

class FiledSheetTest {

    static final String SHEET =
            """
            # the levies, as the commission sent them
            code | rate

            NCB  | 0.0000
              KSCORN |  0.0100
            """;

    @Test
    void onlyTheLinesThatCarrySomethingAreRows() {
        assertThat(FiledSheet.rows(SHEET)).containsExactly("code | rate", "NCB  | 0.0000", "KSCORN |  0.0100");
        assertThat(FiledSheet.rows(SHEET, "code")).containsExactly("NCB  | 0.0000", "KSCORN |  0.0100");
    }

    @Test
    void aHeadingWordOnlyDropsTheLineItStarts() {
        assertThat(FiledSheet.rows("code\ncodes | 1\ncode | 2", "code")).containsExactly("codes | 1");
    }

    @Test
    void cellsAreCutOnBarsOrOnSpaces() {
        assertThat(FiledSheet.barred("KSCORN |  0.0100")).containsExactly("KSCORN", "0.0100");
        assertThat(FiledSheet.barred("a || c")).containsExactly("a", "", "c");
        assertThat(FiledSheet.spaced("CORN   bcfm  3.0")).containsExactly("CORN", "bcfm", "3.0");
        assertThat(FiledSheet.spaced("CORN  bcfm  3.0  broken corn", 4))
                .containsExactly("CORN", "bcfm", "3.0", "broken corn");
    }

    @Test
    void aNumberThatDoesNotReadNamesItsLine() {
        assertThat(FiledSheet.number("0.0100", "KSCORN | 0.0100", IllegalStateException::new))
                .isEqualTo(d("0.0100"));
        assertThatThrownBy(() -> FiledSheet.number("O.01", "KSCORN | O.01", IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("'O.01' is not a number: KSCORN | O.01");
    }

    @Test
    void aDashIsNothing() {
        assertThat(FiledSheet.numberOrNothing("-", "row", IllegalStateException::new))
                .isEmpty();
        assertThat(FiledSheet.numberOrNothing("52.0", "row", IllegalStateException::new))
                .contains(d("52.0"));
    }

    @Test
    void everySheetRefusesABadNumberWithItsLine() {
        assertThatThrownBy(() -> GradeTable.parse("CORN", "grade  test_weight\n1  fifty"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CORN: 'fifty' is not a number: 1  fifty");
        assertThatThrownBy(() -> Commodities.parse("PEAS | Field Peas | sixty | 14.0 | CWT | moisture | -"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'sixty' is not a number: PEAS | Field Peas");
    }
}
