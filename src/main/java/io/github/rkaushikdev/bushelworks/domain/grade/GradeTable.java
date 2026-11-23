package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.commodity.FactorDirection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A grade ladder written the way the standard prints it: a heading of factors and a line a grade.
 *
 * <pre>
 *     grade  test_weight  damage_heat  damage_total  bcfm
 *     1      56.0         0.1          3.0           2.0
 *     2      54.0         0.2          5.0           3.0
 * </pre>
 *
 * <p>The heading names the factors by their certificate values, the first column is the grade,
 * and a dash leaves a factor out of that rung. Blank lines and lines starting with {@code #} are
 * passed over. A ladder in which a lower grade is stricter than the one above it on any factor is
 * a typing mistake, and it is refused here rather than discovered on a load.
 */
public final class GradeTable {

    private static final String GRADE_COLUMN = "grade";
    private static final String NOT_LOOKED_AT = "-";

    private GradeTable() {}

    /**
     * The rungs a table describes, in the order it lists them.
     *
     * @throws IllegalArgumentException naming the commodity and the line, when the table cannot be
     *     read or a lower grade is stricter than a higher one
     */
    public static List<GradeRung> parse(String commodityCode, String table) {
        List<String[]> lines = table.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.split("\\s+"))
                .toList();
        if (lines.size() < 2) {
            throw new IllegalArgumentException(commodityCode + ": a grade table needs a heading and a rung");
        }
        String[] heading = lines.get(0);
        if (!heading[0].equals(GRADE_COLUMN)) {
            throw new IllegalArgumentException(commodityCode + ": a grade table starts with a grade column");
        }
        List<Factor> factors = new ArrayList<>();
        for (int column = 1; column < heading.length; column++) {
            factors.add(Factor.of(heading[column]));
        }
        List<GradeRung> rungs = new ArrayList<>();
        for (String[] cells : lines.subList(1, lines.size())) {
            if (cells.length != heading.length) {
                throw new IllegalArgumentException(commodityCode + ": the line for grade " + cells[0] + " has "
                        + (cells.length - 1) + " limits for " + factors.size() + " factors");
            }
            Map<Factor, BigDecimal> limits = new LinkedHashMap<>();
            for (int column = 1; column < cells.length; column++) {
                if (!cells[column].equals(NOT_LOOKED_AT)) {
                    limits.put(factors.get(column - 1), new BigDecimal(cells[column]));
                }
            }
            rungs.add(new GradeRung(NumericalGrade.of(cells[0]), limits));
        }
        checkLoosening(commodityCode, rungs);
        return List.copyOf(rungs);
    }

    /** Each rung has to let in at least what the rung above it lets in, factor by factor. */
    private static void checkLoosening(String commodityCode, List<GradeRung> listed) {
        List<GradeRung> rungs = listed.stream()
                .sorted(Comparator.comparingInt(rung -> rung.grade().rank()))
                .toList();
        for (int index = 1; index < rungs.size(); index++) {
            GradeRung above = rungs.get(index - 1);
            GradeRung below = rungs.get(index);
            for (Map.Entry<Factor, BigDecimal> limit : below.limits().entrySet()) {
                BigDecimal higher = above.limits().get(limit.getKey());
                if (higher == null) {
                    continue;
                }
                int toward = limit.getValue().compareTo(higher);
                boolean stricter = limit.getKey().direction() == FactorDirection.LOW_IS_BAD ? toward > 0 : toward < 0;
                if (stricter) {
                    throw new IllegalArgumentException(
                            commodityCode + ": US " + below.grade().value()
                                    + " is stricter than US " + above.grade().value() + " on "
                                    + limit.getKey().value());
                }
            }
        }
    }
}
