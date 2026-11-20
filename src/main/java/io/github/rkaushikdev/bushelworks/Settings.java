package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Everything the application needs to start, read from the environment once.
 *
 * <p>Everything here has a default that works on a laptop with no environment set, except the
 * database URL in production. The scale house runs on a box in the office with no operations team,
 * so a setting that has to be discovered by reading a stack trace is a setting that will be wrong.
 *
 * @param databaseUrl the JDBC URL of the database
 * @param echoSql whether the SQL the persistence layer runs is logged
 * @param houseName the elevator's name, as statements print it
 * @param stateCode the two letter state the house files with
 * @param defaultLocation the location a ticket is written at when none is named
 * @param statementPrefix the series letter settlement statements are numbered under
 * @param ticketPrefix the series letter scale tickets are numbered under
 * @param maxPageSize the most rows one page of a listing carries
 * @param logLevel DEBUG, INFO, WARNING or ERROR
 * @param rateLimitPerMinute calls a minute per caller; zero turns the limit off
 * @param apiKeys the keys the API accepts; none means it asks for none
 * @param minimumSettlement the smallest net a settlement run writes a cheque for
 */
public record Settings(
        String databaseUrl,
        boolean echoSql,
        String houseName,
        String stateCode,
        String defaultLocation,
        String statementPrefix,
        String ticketPrefix,
        int maxPageSize,
        String logLevel,
        int rateLimitPerMinute,
        Set<String> apiKeys,
        BigDecimal minimumSettlement) {

    /** Every environment variable this reads starts with this. */
    public static final String PREFIX = "BUSHELWORKS_";

    public static final String DEFAULT_DATABASE_URL = "jdbc:h2:file:./bushelworks";
    public static final String DEFAULT_HOUSE_NAME = "BUSHELWORKS ELEVATOR";
    public static final String DEFAULT_STATE_CODE = "KS";
    public static final String DEFAULT_LOCATION = "HOME";
    public static final String DEFAULT_STATEMENT_PREFIX = "S";
    public static final String DEFAULT_TICKET_PREFIX = "A";
    public static final int DEFAULT_MAX_PAGE_SIZE = 200;
    public static final String DEFAULT_LOG_LEVEL = "INFO";

    /**
     * Calls a minute per caller. Zero turns the limit off, which is what a house with only a scale
     * house terminal and an office wants.
     */
    public static final int DEFAULT_RATE_LIMIT = 0;

    public static final List<String> LOG_LEVELS = List.of("DEBUG", "INFO", "WARNING", "ERROR");

    public Settings {
        Objects.requireNonNull(databaseUrl, "databaseUrl");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(stateCode, "stateCode");
        apiKeys = Set.copyOf(apiKeys);
        if (databaseUrl.isEmpty()) {
            throw new ConfigurationError("a database url is required");
        }
        if (!LOG_LEVELS.contains(logLevel)) {
            throw new ConfigurationError(
                    "log level '" + logLevel + "' should be one of " + String.join(", ", LOG_LEVELS));
        }
        if (stateCode.length() != 2) {
            throw new ConfigurationError("state code '" + stateCode + "' should be two letters");
        }
        if (rateLimitPerMinute < 0) {
            throw new ConfigurationError("a rate limit cannot be negative");
        }
        if (maxPageSize < 1) {
            throw new ConfigurationError("max page size has to be at least one");
        }
        if (minimumSettlement.signum() < 0) {
            throw new ConfigurationError("minimum settlement cannot be negative");
        }
    }

    /** The settings a laptop with nothing set gets. */
    public static Settings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** These settings, ready to have one or two changed. */
    public Builder toBuilder() {
        return new Builder()
                .databaseUrl(databaseUrl)
                .echoSql(echoSql)
                .houseName(houseName)
                .stateCode(stateCode)
                .defaultLocation(defaultLocation)
                .statementPrefix(statementPrefix)
                .ticketPrefix(ticketPrefix)
                .maxPageSize(maxPageSize)
                .logLevel(logLevel)
                .rateLimitPerMinute(rateLimitPerMinute)
                .apiKeys(apiKeys)
                .minimumSettlement(minimumSettlement);
    }

    /** The same settings over another database. */
    public Settings withDatabaseUrl(String url) {
        return toBuilder().databaseUrl(url).build();
    }

    /** Whether the database is an H2 database in this process, in memory or in a file. */
    public boolean isEmbedded() {
        return databaseUrl.startsWith("jdbc:h2:");
    }

    public boolean requiresApiKey() {
        return !apiKeys.isEmpty();
    }

    /** Read the process environment into settings, refusing anything unusable. */
    public static Settings load() {
        return load(System::getenv);
    }

    /**
     * Read an environment into settings, refusing anything unusable.
     *
     * @param environment the value of a variable, or null when it is not set
     */
    public static Settings load(UnaryOperator<String> environment) {
        Environment env = new Environment(environment);
        String keys = env.text("API_KEYS", "");
        return builder()
                .databaseUrl(env.textOr("DATABASE_URL", DEFAULT_DATABASE_URL))
                .echoSql(env.flag("ECHO_SQL", false))
                .houseName(env.textOr("HOUSE_NAME", DEFAULT_HOUSE_NAME))
                .stateCode(env.textOr("STATE_CODE", DEFAULT_STATE_CODE).toUpperCase(Locale.ROOT))
                .defaultLocation(env.textOr("DEFAULT_LOCATION", DEFAULT_LOCATION))
                .statementPrefix(env.textOr("STATEMENT_PREFIX", DEFAULT_STATEMENT_PREFIX))
                .ticketPrefix(env.textOr("TICKET_PREFIX", DEFAULT_TICKET_PREFIX))
                .maxPageSize(env.whole("MAX_PAGE_SIZE", DEFAULT_MAX_PAGE_SIZE))
                .logLevel(env.textOr("LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase(Locale.ROOT))
                .rateLimitPerMinute(env.whole("RATE_LIMIT_PER_MINUTE", DEFAULT_RATE_LIMIT))
                .apiKeys(Arrays.stream(keys.split(",", -1))
                        .map(String::strip)
                        .filter(key -> !key.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()))
                .minimumSettlement(env.number("MINIMUM_SETTLEMENT", "0.00"))
                .build();
    }

    private record Environment(UnaryOperator<String> lookup) {

        String text(String name, String fallback) {
            String value = lookup.apply(PREFIX + name);
            return value == null ? fallback : value;
        }

        /** The value, or the fallback when it is unset or empty. */
        String textOr(String name, String fallback) {
            String value = lookup.apply(PREFIX + name);
            return value == null || value.isEmpty() ? fallback : value;
        }

        boolean flag(String name, boolean fallback) {
            String raw = lookup.apply(PREFIX + name);
            if (raw == null) {
                return fallback;
            }
            String lowered = raw.strip().toLowerCase(Locale.ROOT);
            if (List.of("1", "true", "yes", "on").contains(lowered)) {
                return true;
            }
            if (List.of("0", "false", "no", "off").contains(lowered)) {
                return false;
            }
            throw new ConfigurationError(PREFIX + name + " should be a yes or a no, got '" + raw + "'");
        }

        int whole(String name, int fallback) {
            String raw = lookup.apply(PREFIX + name);
            if (raw == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException trouble) {
                throw new ConfigurationError(PREFIX + name + " should be a whole number", trouble);
            }
        }

        BigDecimal number(String name, String fallback) {
            String raw = textOr(name, fallback);
            try {
                return Decimals.parse(raw);
            } catch (NumberFormatException trouble) {
                throw new ConfigurationError(PREFIX + name + " should be a number", trouble);
            }
        }
    }

    /** Settings built a field at a time, each starting at its default. */
    public static final class Builder {

        private String databaseUrl = DEFAULT_DATABASE_URL;
        private boolean echoSql;
        private String houseName = DEFAULT_HOUSE_NAME;
        private String stateCode = DEFAULT_STATE_CODE;
        private String defaultLocation = DEFAULT_LOCATION;
        private String statementPrefix = DEFAULT_STATEMENT_PREFIX;
        private String ticketPrefix = DEFAULT_TICKET_PREFIX;
        private int maxPageSize = DEFAULT_MAX_PAGE_SIZE;
        private String logLevel = DEFAULT_LOG_LEVEL;
        private int rateLimitPerMinute = DEFAULT_RATE_LIMIT;
        private Set<String> apiKeys = Set.of();
        private BigDecimal minimumSettlement = new BigDecimal("0.00");

        private Builder() {}

        public Builder databaseUrl(String value) {
            this.databaseUrl = value;
            return this;
        }

        public Builder echoSql(boolean value) {
            this.echoSql = value;
            return this;
        }

        public Builder houseName(String value) {
            this.houseName = value;
            return this;
        }

        public Builder stateCode(String value) {
            this.stateCode = value;
            return this;
        }

        public Builder defaultLocation(String value) {
            this.defaultLocation = value;
            return this;
        }

        public Builder statementPrefix(String value) {
            this.statementPrefix = value;
            return this;
        }

        public Builder ticketPrefix(String value) {
            this.ticketPrefix = value;
            return this;
        }

        public Builder maxPageSize(int value) {
            this.maxPageSize = value;
            return this;
        }

        public Builder logLevel(String value) {
            this.logLevel = value;
            return this;
        }

        public Builder rateLimitPerMinute(int value) {
            this.rateLimitPerMinute = value;
            return this;
        }

        public Builder apiKeys(Set<String> value) {
            this.apiKeys = value;
            return this;
        }

        public Builder minimumSettlement(BigDecimal value) {
            this.minimumSettlement = value;
            return this;
        }

        public Settings build() {
            return new Settings(
                    databaseUrl,
                    echoSql,
                    houseName,
                    stateCode,
                    defaultLocation,
                    statementPrefix,
                    ticketPrefix,
                    maxPageSize,
                    logLevel,
                    rateLimitPerMinute,
                    apiKeys,
                    minimumSettlement);
        }
    }
}
