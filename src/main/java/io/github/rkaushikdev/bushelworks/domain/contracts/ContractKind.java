package io.github.rkaushikdev.bushelworks.domain.contracts;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * The five shapes of purchase contract, which differ in which half of the price is fixed and when.
 *
 * <ul>
 *   <li>{@link #CASH} fixes both halves today and delivers today.
 *   <li>{@link #FORWARD} fixes both halves today and delivers in a window.
 *   <li>{@link #HTA} fixes the futures half now and leaves the basis to be set later.
 *   <li>{@link #BASIS} fixes the basis half now and leaves the futures to be set later.
 *   <li>{@link #DEFERRED} fixes neither and charges a service fee for the privilege.
 * </ul>
 */
public enum ContractKind implements ValueEnum {
    CASH("cash"),
    FORWARD("forward"),
    HTA("hta"),
    BASIS("basis"),
    DEFERRED("deferred");

    private final String value;

    ContractKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static ContractKind of(String value) {
        return ValueEnum.of(ContractKind.class, value);
    }
}
