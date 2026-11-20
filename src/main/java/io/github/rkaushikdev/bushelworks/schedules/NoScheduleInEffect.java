package io.github.rkaushikdev.bushelworks.schedules;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Raised when nothing is filed for a key on the date asked about. */
public class NoScheduleInEffect extends LookupFailure {

    public NoScheduleInEffect(String message) {
        super(message);
    }
}
