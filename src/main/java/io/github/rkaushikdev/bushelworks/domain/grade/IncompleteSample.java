package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a required factor was never recorded on the sample. */
public class IncompleteSample extends ValidationError {

    public IncompleteSample(String message) {
        super(message);
    }
}
