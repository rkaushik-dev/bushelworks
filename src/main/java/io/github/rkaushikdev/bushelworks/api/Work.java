package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.util.function.Function;

/**
 * One unit of work per request, closed when the route is done with it.
 *
 * <p>Routes commit explicitly, and nothing commits on their behalf: a route that refuses halfway
 * through leaves nothing behind, because closing an uncommitted unit rolls it back. That is also why
 * a read can safely run a service that writes: the close sheet runs the whole close to print it and
 * keeps none of it. A route that takes several documents in one call commits each as it goes and
 * rolls back only the one that was refused.
 *
 * <pre>{@code
 * return work.apply(uow -> {
 *     Party row = Parties.openAccount(uow, opening);
 *     PartyOut out = partyOut(row);
 *     uow.commit();
 *     return out;
 * });
 * }</pre>
 *
 * <p>The commit runs inside the route, before its answer is written, so a commit that fails is
 * reported as the failure it is rather than following a 200 that has already gone out.
 */
public final class Work {

    private final Database database;

    public Work(Database database) {
        this.database = database;
    }

    /** Run {@code route} in a fresh unit of work and answer what it answers. */
    public <T> T apply(Function<UnitOfWork, T> route) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return route.apply(uow);
        }
    }
}
