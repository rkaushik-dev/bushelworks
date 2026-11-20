package io.github.rkaushikdev.bushelworks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base for every error raised on purpose.
 *
 * <p>Each error carries a {@link #code()}. The API layer maps codes to responses and the statement
 * printer maps them to something a grain accountant can act on, and neither has to match on
 * message text. A class that does not name a code with {@link ErrorCode} gets one derived from its
 * class name ({@code ContractError} reports {@code contract_error}), so a new error is never
 * silently reported as its parent's code. An instance may override the code it was given.
 */
@ErrorCode("bushelworks_error")
public class BushelworksError extends RuntimeException {

    private final String message;
    private final String code;

    public BushelworksError(String message) {
        this(message, null, null);
    }

    public BushelworksError(String message, String code) {
        this(message, code, null);
    }

    public BushelworksError(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * An error with a message, an instance code that overrides the class's own when it is not
     * null, and the failure that caused it.
     */
    public BushelworksError(String message, String code, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.message = message;
        this.code = code != null ? code : codeOf(getClass());
    }

    /** The code a class reports: the one it names, or the one its simple name gives. */
    public static String codeOf(Class<? extends BushelworksError> type) {
        ErrorCode named = type.getDeclaredAnnotation(ErrorCode.class);
        if (named != null) {
            return named.value();
        }
        return snakeCase(type.getSimpleName());
    }

    /** {@code ContractError} as {@code contract_error}. */
    static String snakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            if (Character.isUpperCase(letter) && index > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(letter));
        }
        return out.toString();
    }

    /** The stable code callers branch on. */
    public String code() {
        return code;
    }

    /** The sentence, with nothing added to it. */
    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return message;
    }

    /** The code and the message, in that order, as the API writes them. */
    public Map<String, String> asMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        return out;
    }
}
