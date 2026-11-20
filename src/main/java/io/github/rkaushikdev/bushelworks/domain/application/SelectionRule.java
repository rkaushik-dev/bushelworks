package io.github.rkaushikdev.bushelworks.domain.application;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * Which open contract a delivery fills first when the driver names none.
 *
 * <ul>
 *   <li>{@link #WINDOW} fills the contract whose delivery window closes first, which keeps the house
 *       out of trouble with the terminal.
 *   <li>{@link #OLDEST} fills the contract written first.
 *   <li>{@link #BEST_PRICE} fills the highest priced contract, which is the rule a producer assumes
 *       is in force whether or not anyone told him it is.
 * </ul>
 */
public enum SelectionRule implements ValueEnum {
    WINDOW("window"),
    OLDEST("oldest"),
    BEST_PRICE("best_price");

    private final String value;

    SelectionRule(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static SelectionRule of(String value) {
        return ValueEnum.of(SelectionRule.class, value);
    }
}
