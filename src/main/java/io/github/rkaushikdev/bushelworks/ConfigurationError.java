package io.github.rkaushikdev.bushelworks;

/** The house is set up in a way the package cannot work with. */
@ErrorCode("configuration_error")
public class ConfigurationError extends BushelworksError {

    public ConfigurationError(String message) {
        super(message);
    }

    public ConfigurationError(String message, Throwable cause) {
        super(message, cause);
    }
}
