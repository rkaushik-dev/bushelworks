package io.github.rkaushikdev.bushelworks.domain.commodity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The commodity master: the commodities the house buys, by code.
 *
 * <p>These are US standards. A house that buys canola or field peas adds a row; it does not change
 * the shape of the row. The master is kept as the sheet the office prints, a row a commodity with
 * the columns between bars:
 *
 * <pre>
 *     code | name | lb/bu | base moisture | priced per | required | optional
 * </pre>
 *
 * <p>Factors are named by their certificate values, separated by spaces, and a dash is none. The
 * counts and percentages that make any load Sample Grade are optional on every commodity whether
 * the row says so or not.
 */
public final class Commodities {

    private static final Map<String, Commodity> MASTER = new ConcurrentHashMap<>();

    /** The master as the office prints it. */
    public static final String MASTER_SHEET =
            """
            code | name                  | lb/bu | base moisture | priced per | required                                                                 | optional
            CORN | Yellow Corn           | 56    | 15.0          | BU         | test_weight moisture bcfm damage_total                                   | damage_heat
            SOYB | Yellow Soybeans       | 60    | 13.0          | BU         | test_weight moisture foreign_material damage_total splits                | damage_heat oil
            HRW  | Hard Red Winter Wheat | 60    | 13.5          | BU         | test_weight moisture dockage damage_total shrunken_broken total_defects  | protein falling_number damage_heat
            MILO | Grain Sorghum         | 56    | 14.0          | BU         | test_weight moisture foreign_material damage_total                       | -
            SUNF | Oil Sunflower Seed    | 25    | 10.0          | CWT        | moisture foreign_material damage_total                                   | oil
            """;

    private static final int COLUMNS = 7;

    static {
        parse(MASTER_SHEET).forEach(Commodities::register);
    }

    public static final Commodity CORN = get("CORN");

    public static final Commodity SOYBEANS = get("SOYB");

    public static final Commodity HRW_WHEAT = get("HRW");

    public static final Commodity GRAIN_SORGHUM = get("MILO");

    public static final Commodity SUNFLOWER = get("SUNF");

    private Commodities() {}

    /**
     * The commodities a master sheet describes, in the order it lists them, without putting any of
     * them on the master.
     *
     * @throws IllegalArgumentException naming the line, when a row cannot be read or a code comes
     *     twice
     */
    public static List<Commodity> parse(String sheet) {
        List<Commodity> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : sheet.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("code ")) {
                continue;
            }
            String[] cells =
                    Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
            if (cells.length != COLUMNS) {
                throw new IllegalArgumentException(
                        "a commodity row needs " + COLUMNS + " columns, not " + cells.length + ": " + line);
            }
            if (!seen.add(cells[0])) {
                throw new IllegalArgumentException(cells[0] + " is on the master sheet twice");
            }
            rows.add(new Commodity(
                    cells[0],
                    cells[1],
                    new BigDecimal(cells[2]),
                    new BigDecimal(cells[3]),
                    PriceUnit.of(cells[4]),
                    factors(cells[5]),
                    optional(factors(cells[6]))));
        }
        return List.copyOf(rows);
    }

    private static Set<Factor> factors(String cell) {
        if (cell.equals("-")) {
            return Set.of();
        }
        Set<Factor> named = EnumSet.noneOf(Factor.class);
        for (String value : cell.split("\\s+")) {
            named.add(Factor.of(value));
        }
        return named;
    }

    /**
     * A commodity's optional factors, with the counts and percentages that can make any load Sample
     * Grade, which a grader may always record.
     */
    private static Set<Factor> optional(Set<Factor> factors) {
        Set<Factor> all = EnumSet.of(
                Factor.STONES,
                Factor.GLASS,
                Factor.CROTALARIA,
                Factor.CASTOR_BEANS,
                Factor.UNKNOWN_FOREIGN,
                Factor.ANIMAL_FILTH);
        all.addAll(factors);
        return all;
    }

    /** Put a commodity on the master, replacing one with the same code. */
    public static Commodity register(Commodity commodity) {
        MASTER.put(commodity.code(), commodity);
        return commodity;
    }

    /** The commodity with this code. */
    public static Commodity get(String code) {
        Commodity found = MASTER.get(code);
        if (found == null) {
            throw new UnknownCommodity(code);
        }
        return found;
    }

    /** Every code on the master, sorted. */
    public static List<String> codes() {
        return MASTER.keySet().stream().sorted().toList();
    }
}
