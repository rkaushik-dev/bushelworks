package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when an assessment cannot be worked out as configured. */
public class AssessmentError extends ValidationError {

    public AssessmentError(String message) {
        super(message);
    }
}
