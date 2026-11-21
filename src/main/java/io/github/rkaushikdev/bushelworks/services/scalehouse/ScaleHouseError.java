package io.github.rkaushikdev.bushelworks.services.scalehouse;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a load cannot be taken in, or priced, as presented. */
@ErrorCode("scale_house_error")
public class ScaleHouseError extends ValidationError {

    public ScaleHouseError(String message) {
        super(message);
    }
}
