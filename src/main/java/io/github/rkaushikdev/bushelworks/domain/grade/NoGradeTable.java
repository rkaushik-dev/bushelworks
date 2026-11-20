package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Raised when a commodity has no grade ladder loaded. */
public class NoGradeTable extends LookupFailure {

    public NoGradeTable(String message) {
        super(message);
    }
}
