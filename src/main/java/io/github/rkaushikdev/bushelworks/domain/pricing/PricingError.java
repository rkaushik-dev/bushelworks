package io.github.rkaushikdev.bushelworks.domain.pricing;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a price cannot be assembled from what is on file. */
public class PricingError extends ValidationError {

    public PricingError(String message) {
        super(message);
    }
}
