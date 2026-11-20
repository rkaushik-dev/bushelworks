package io.github.rkaushikdev.bushelworks;

/**
 * Scale house capture and settlement for country grain elevators.
 *
 * <p>The version lives here and nowhere else. The build reads the same number, the API reports it
 * on {@code /health} and the command line prints it, so there is one place to change it and no
 * chance of the three disagreeing.
 */
public final class Bushelworks {

    /** The release this build is. */
    public static final String VERSION = "0.8.0";

    private Bushelworks() {}

    /** A release number as numbers, for a caller that wants to compare it. */
    public record Version(int major, int minor, int patch) implements Comparable<Version> {

        /** Read {@code 0.8.0}. */
        public static Version parse(String text) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 3) {
                throw new ValidationError("a version is three numbers with dots between: " + text);
            }
            try {
                return new Version(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException trouble) {
                throw new ValidationError("a version is three numbers with dots between: " + text, trouble);
            }
        }

        @Override
        public int compareTo(Version other) {
            int major = Integer.compare(this.major, other.major);
            if (major != 0) {
                return major;
            }
            int minor = Integer.compare(this.minor, other.minor);
            return minor != 0 ? minor : Integer.compare(this.patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /** This build's version as numbers. */
    public static Version versionTuple() {
        return Version.parse(VERSION);
    }
}
