package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a settlement cannot be turned into a journal entry. */
@ErrorCode("posting_error")
public class PostingError extends ValidationError {

    public PostingError(String message) {
        super(message);
    }
}
