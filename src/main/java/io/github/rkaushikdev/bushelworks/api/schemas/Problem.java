package io.github.rkaushikdev.bushelworks.api.schemas;

import java.util.Objects;
import java.util.Optional;

/**
 * A refusal, in the shape every one of them comes back in.
 *
 * @param code the stable code a caller branches on
 * @param detail the sentence whoever is at the window is shown
 * @param requestId the request it ended, when the request had an id
 */
public record Problem(String code, String detail, Optional<String> requestId) {

    public Problem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(requestId, "requestId");
    }

    /** A refusal of no request in particular. */
    public static Problem of(String code, String detail) {
        return new Problem(code, detail, Optional.empty());
    }
}
