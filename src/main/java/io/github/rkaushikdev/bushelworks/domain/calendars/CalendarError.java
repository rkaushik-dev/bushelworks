package io.github.rkaushikdev.bushelworks.domain.calendars;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a date cannot be resolved on this calendar. */
@ErrorCode("calendar_error")
public class CalendarError extends ValidationError {

    public CalendarError(String message) {
        super(message);
    }
}
