package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a schedule is not usable as written. */
public class ScheduleError extends ValidationError {

    public ScheduleError(String message) {
        super(message);
    }
}
