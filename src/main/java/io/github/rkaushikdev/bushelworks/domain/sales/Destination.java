package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Who the grain is going to. */
public enum Destination implements ValueEnum {
    TERMINAL("terminal"),
    PROCESSOR("processor"),
    FEEDLOT("feedlot"),
    EXPORT("export");

    private final String value;

    Destination(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Destination of(String value) {
        return ValueEnum.of(Destination.class, value);
    }
}
