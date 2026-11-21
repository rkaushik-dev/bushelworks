package io.github.rkaushikdev.bushelworks.api.security;

import java.util.Objects;

/**
 * Who is on the other end of a request.
 *
 * @param keyId enough of the key to tell keys apart in a log, never the whole secret
 * @param role what the key may do
 */
public record Caller(String keyId, Role role) {

    /** Whoever calls a house that asks for no key: trusted with everything, as before keys existed. */
    public static final Caller ANONYMOUS = new Caller("anonymous", Role.OFFICE);

    public Caller {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(role, "role");
    }

    /** Whether this caller may do what {@code needed} may: anything at or below its own role. */
    public boolean can(Role needed) {
        return role.rank() >= needed.rank();
    }
}
