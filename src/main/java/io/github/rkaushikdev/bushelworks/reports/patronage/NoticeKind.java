package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Which kind of written notice of allocation a patronage refund goes out on. */
public enum NoticeKind implements ValueEnum {
    QUALIFIED("qualified"),
    NONQUALIFIED("nonqualified");

    private final String value;

    NoticeKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static NoticeKind of(String value) {
        return ValueEnum.of(NoticeKind.class, value);
    }
}
