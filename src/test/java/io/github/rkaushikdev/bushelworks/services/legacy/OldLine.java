package io.github.rkaushikdev.bushelworks.services.legacy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of the old system's ticket file, written field by field, padding included, so a test can
 * change one field and leave the rest as a clean load of corn.
 *
 * <p>The fields are written in the order the old file has them, independently of {@link
 * LegacyReader#LAYOUT}, so a test of the layout is not checking the layout against itself.
 */
final class OldLine {

    private OldLine() {}

    /**
     * A clean line scaled on {@code scaleDate}, with any field replaced: {@code text("20221014",
     * "ticket", "A-10442   ")}. The fields are ticket, scale_date, party, commodity, location, gross,
     * tare, moisture, test_weight, damage, disposition and void.
     */
    static String text(String scaleDate, String... overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ticket", "A-10441   ");
        fields.put("scale_date", scaleDate);
        fields.put("party", "MRK001");
        fields.put("commodity", "CORN");
        fields.put("location", "HOME");
        fields.put("gross", "     78420");
        fields.put("tare", "     26180");
        fields.put("moisture", "  18.2");
        fields.put("test_weight", "  56.5");
        fields.put("damage", "   1.0");
        fields.put("disposition", "CASH    ");
        fields.put("void", " ");
        if (overrides.length % 2 != 0) {
            throw new IllegalArgumentException("overrides come in field and value pairs");
        }
        for (int index = 0; index < overrides.length; index += 2) {
            if (!fields.containsKey(overrides[index])) {
                throw new IllegalArgumentException("the old file has no field " + overrides[index]);
            }
            fields.put(overrides[index], overrides[index + 1]);
        }
        return String.join("", fields.values());
    }
}
