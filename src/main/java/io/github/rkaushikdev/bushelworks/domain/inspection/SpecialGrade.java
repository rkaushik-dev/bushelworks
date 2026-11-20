package io.github.rkaushikdev.bushelworks.domain.inspection;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.util.EnumSet;
import java.util.Set;

/**
 * The words that go after the number on a certificate.
 *
 * <p>The special grades are the part that surprises people. A load can be a US No. 2 and still be
 * refused, because "US No. 2 Yellow Corn, Weevily" is not the same thing as "US No. 2 Yellow Corn".
 */
public enum SpecialGrade implements ValueEnum {
    INFESTED("infested"),
    WEEVILY("weevily"),
    HEATING("heating"),
    MUSTY("musty"),
    SOUR("sour"),
    LIGHT_DAMAGE("light_damage"),
    TREATED("treated"),
    GARLICKY("garlicky");

    /** Special grades that let a buyer refuse a load outright rather than discount it. */
    public static final Set<SpecialGrade> REJECTING = Set.copyOf(EnumSet.of(INFESTED, WEEVILY, HEATING, TREATED));

    private final String value;

    SpecialGrade(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static SpecialGrade of(String value) {
        return ValueEnum.of(SpecialGrade.class, value);
    }
}
