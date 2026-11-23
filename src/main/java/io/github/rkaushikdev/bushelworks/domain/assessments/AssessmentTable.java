package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.FiledSheet;
import java.util.ArrayList;
import java.util.List;

/**
 * The levies a house collects, written as the rate sheet the commissions send out: a line a levy,
 * the columns between bars.
 *
 * <pre>
 *     code | name | authority | commodity | state | basis | rate | on | refundable
 * </pre>
 *
 * <p>The state is a two letter code or {@code *} for every state, the basis and the value it is
 * levied on are written as their values, and refundable is {@code yes} or {@code no}. Blank
 * lines, lines starting with {@code #} and the heading are passed over.
 */
public final class AssessmentTable {

    private static final int COLUMNS = 9;

    private AssessmentTable() {}

    /**
     * The levies a sheet describes, in the order it lists them.
     *
     * @throws AssessmentError naming the line, when a line cannot be read
     */
    public static List<Assessment> parse(String sheet) {
        List<Assessment> levies = new ArrayList<>();
        for (String line : FiledSheet.rows(sheet, "code")) {
            String[] cells = FiledSheet.barred(line);
            if (cells.length != COLUMNS) {
                throw new AssessmentError("a levy needs " + COLUMNS + " columns, not " + cells.length + ": " + line);
            }
            levies.add(new Assessment(
                    cells[0],
                    cells[1],
                    cells[2],
                    cells[3],
                    FiledSheet.number(cells[6], line, AssessmentError::new),
                    AssessmentBasis.of(cells[5]),
                    ValueBasis.of(cells[7]),
                    refundable(cells[8], line),
                    cells[4]));
        }
        return List.copyOf(levies);
    }

    private static boolean refundable(String cell, String line) {
        return switch (cell) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new AssessmentError("refundable is yes or no, not '" + cell + "': " + line);
        };
    }
}
