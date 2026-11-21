package io.github.rkaushikdev.bushelworks.api;

import java.util.List;

/**
 * A request that could not be read into the shape its route takes: a field missing, a date that is
 * not a date, a quantity sent as a JSON number.
 *
 * <p>It is not a domain error. The request was not understood, so nothing in the house was asked
 * anything; {@link Errors} answers it with a 422 whose body names every refused field.
 */
public class RequestRefused extends RuntimeException {

    private final transient List<Refusal> refusals;

    /** A request refused for every one of {@code refusals}, which may not be empty. */
    public RequestRefused(List<Refusal> refusals) {
        super(Errors.sentence(refusals));
        this.refusals = List.copyOf(refusals);
    }

    /** A request refused for one thing. */
    public static RequestRefused of(Refusal refusal) {
        return new RequestRefused(List.of(refusal));
    }

    /** What was wrong, in the order it was found. */
    public List<Refusal> refusals() {
        return refusals;
    }
}
