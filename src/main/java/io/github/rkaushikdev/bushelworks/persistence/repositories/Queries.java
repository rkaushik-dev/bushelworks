package io.github.rkaushikdev.bushelworks.persistence.repositories;

import jakarta.persistence.TypedQuery;
import java.util.Optional;

/** The one-row read every repository needs. */
final class Queries {

    private Queries() {}

    /** The first row the query gives, if it gives any. */
    static <T> Optional<T> first(TypedQuery<T> query) {
        return query.getResultList().stream().findFirst();
    }
}
