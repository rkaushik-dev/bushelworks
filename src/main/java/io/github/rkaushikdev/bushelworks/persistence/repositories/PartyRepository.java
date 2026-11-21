package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.persistence.models.Location;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import jakarta.persistence.EntityManager;
import java.util.List;

/**
 * Parties and locations, which are the two things everything else points at.
 *
 * <p>Read mostly: the producer file changes a handful of times a year and the location file changes
 * when the house buys another elevator.
 */
public final class PartyRepository {

    private final EntityManager session;

    public PartyRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * The party filed under {@code code}.
     *
     * @throws PartyNotFound when there is none
     */
    public Party get(String code) {
        return Queries.first(session.createQuery("select p from Party p where p.code = :code", Party.class)
                        .setParameter("code", code))
                .orElseThrow(() -> new PartyNotFound("no party " + code));
    }

    /** The active parties, in sort name order. */
    public List<Party> active() {
        return session.createQuery("select p from Party p where p.active = true order by p.sortName", Party.class)
                .getResultList();
    }

    /** The active locations, in code order. */
    public List<Location> locations() {
        return session.createQuery("select l from Location l where l.active = true order by l.code", Location.class)
                .getResultList();
    }
}
