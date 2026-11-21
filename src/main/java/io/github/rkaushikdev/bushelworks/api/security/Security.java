package io.github.rkaushikdev.bushelworks.api.security;

import io.github.rkaushikdev.bushelworks.Quoting;
import io.github.rkaushikdev.bushelworks.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Who is allowed to call, and what they are allowed to do.
 *
 * <p>The scale house runs on a private network and for years the answer was "anybody who can reach
 * it". That stopped being true when the producer portal went up, so keys carry a {@link Role} and
 * the routes that move money ask for one.
 *
 * <p>Keys are compared in constant time. The timing difference of a plain equality check is not
 * exploitable over the office network, and it costs nothing to not have to argue about that.
 */
public final class Security {

    /** The header a key is sent in. */
    public static final String HEADER = "X-API-Key";

    private Security() {}

    /**
     * Read {@code id:role:secret} entries out of a comma separated key list, each secret mapped to
     * its role. A bare secret with no role is an office key, which is what every key was before
     * roles existed, so nobody has to reissue anything. Blank entries are skipped.
     *
     * @throws IllegalArgumentException for an entry with two parts or more than three, or a role
     *     that does not exist
     */
    public static Map<String, Role> parseKeys(String raw) {
        Map<String, Role> keys = new LinkedHashMap<>();
        for (String piece : raw.split(",", -1)) {
            String entry = piece.strip();
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split(":", -1);
            if (parts.length == 1) {
                keys.put(parts[0], Role.OFFICE);
                continue;
            }
            if (parts.length != 3) {
                throw new IllegalArgumentException("cannot read an api key out of " + Quoting.quoted(entry));
            }
            keys.put(parts[2], Role.of(parts[1]));
        }
        return keys;
    }

    /**
     * Each key the settings carry mapped to its role. Read when asked rather than when the settings
     * are, so the settings stay plain values and this class owns the format.
     */
    public static Map<String, Role> keyRoles(Settings settings) {
        return parseKeys(String.join(",", new TreeSet<>(settings.apiKeys())));
    }

    /**
     * Turn a presented key into a caller, or refuse.
     *
     * @param presented the header's value, or null when none was sent
     * @throws NotAuthorised when the house wants a key and none, or one not on file, was sent
     */
    public static Caller resolve(Settings settings, String presented) {
        if (!settings.requiresApiKey()) {
            return Caller.ANONYMOUS;
        }
        if (presented == null || presented.isEmpty()) {
            throw new NotAuthorised("this house requires an " + HEADER + " header");
        }
        byte[] offered = presented.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, Role> key : keyRoles(settings).entrySet()) {
            if (MessageDigest.isEqual(key.getKey().getBytes(StandardCharsets.UTF_8), offered)) {
                return new Caller(keyId(key.getKey()), key.getValue());
            }
        }
        throw new NotAuthorised("that API key is not on file");
    }

    /**
     * Refuse a caller who is not senior enough for {@code needed}.
     *
     * @throws Forbidden naming the role needed and the role the key has
     */
    public static void require(Caller caller, Role needed) {
        if (!caller.can(needed)) {
            throw new Forbidden("this needs the " + needed.value() + " role and that key is "
                    + caller.role().value());
        }
    }

    /** The first four characters of a secret and an ellipsis: enough to tell keys apart, not to use one. */
    private static String keyId(String secret) {
        return secret.substring(0, Math.min(4, secret.length())) + "...";
    }
}
