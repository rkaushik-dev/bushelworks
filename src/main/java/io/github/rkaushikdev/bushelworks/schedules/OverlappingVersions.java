package io.github.rkaushikdev.bushelworks.schedules;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when two versions of one key claim the same day. */
public class OverlappingVersions extends ValidationError {

    public OverlappingVersions(String message) {
        super(message);
    }
}
