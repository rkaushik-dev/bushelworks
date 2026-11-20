package io.github.rkaushikdev.bushelworks.domain.bench;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a sample cannot be worked as asked. */
@ErrorCode("bench_error")
public class BenchError extends ValidationError {

    public BenchError(String message) {
        super(message);
    }
}
