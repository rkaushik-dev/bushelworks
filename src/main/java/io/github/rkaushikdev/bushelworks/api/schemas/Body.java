package io.github.rkaushikdev.bushelworks.api.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Quoting;
import io.github.rkaushikdev.bushelworks.ValueEnum;
import io.github.rkaushikdev.bushelworks.api.Refusal;
import io.github.rkaushikdev.bushelworks.api.RequestRefused;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One JSON object read field by field into a request shape, every refusal collected before any is
 * reported.
 *
 * <p>A driver at the window whose ticket came back with a bad date and a blank number wants to hear
 * about both at once, so a shape reads every field before anything is refused. Each reader returns
 * the field's value, or null (an empty {@code Optional} for an optional field) when the field was
 * refused, and notes the refusal against the field's place in the body. The shape then builds itself
 * through {@link #build}, which builds nothing once anything has been refused, and {@link #read}
 * refuses the whole body with every refusal noted.
 *
 * <pre>{@code
 * static SplitIn read(Body in) {
 *     String partyCode = in.text("party_code", 1, 24);
 *     BigDecimal percent = in.quantity("percent");
 *     SplitRole role = in.choice("role", SplitRole.class, SplitRole.OWNER);
 *     return in.build(() -> new SplitIn(partyCode, percent, role));
 * }
 * }</pre>
 *
 * <p>Quantities are accepted as strings or whole JSON numbers, never as JSON numbers with a fraction
 * or an exponent: those have already been through a double, and the caller is told to send a string.
 */
public final class Body {

    /** What an absent required field is refused with. */
    public static final String FIELD_REQUIRED = "Field required";

    /** What a date that cannot be read is refused with. */
    public static final String NOT_A_DATE = "Input should be a valid date in the format YYYY-MM-DD";

    /** What a moment that cannot be read is refused with. */
    public static final String NOT_A_MOMENT = "Input should be a valid datetime";

    static final String NOT_AN_OBJECT = "Input should be a valid dictionary or object to extract fields from";

    static final String NOT_TEXT = "Input should be a valid string";

    static final String NOT_A_LIST = "Input should be a valid list";

    static final String NOT_A_DICTIONARY = "Input should be a valid dictionary";

    static final String NOT_A_FLAG = "Input should be a valid boolean";

    static final String NOT_WHOLE = "Input should be a valid integer";

    /** What a quantity sent as a JSON number with a fraction is refused with. */
    public static final String NOT_AS_A_STRING = "Value error, send quantities as strings, not as JSON numbers,"
            + " so they do not lose precision on the way in";

    /** An ISO date and time of day, with its offset from UTC or without one, which is read as UTC. */
    private static final DateTimeFormatter MOMENT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .optionalStart()
            .appendOffsetId()
            .optionalEnd()
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
            .toFormatter(Locale.ROOT);

    private static final List<String> TRUE = List.of("true", "1", "yes", "on", "t", "y");
    private static final List<String> FALSE = List.of("false", "0", "no", "off", "f", "n");

    private final JsonNode object;
    private final List<Object> location;
    private final List<Refusal> refusals;

    private Body(JsonNode object, List<Object> location, List<Refusal> refusals) {
        this.object = object;
        this.location = List.copyOf(location);
        this.refusals = refusals;
    }

    /**
     * Read a request body into a shape, or refuse it with everything that was wrong.
     *
     * @param json the body as it arrived; null when there was none
     * @param shape reads its fields from the body and builds itself
     * @throws RequestRefused naming every refused field, under {@code body}
     */
    public static <T> T read(JsonNode json, Function<Body, T> shape) {
        List<Refusal> refusals = new ArrayList<>();
        T read = null;
        if (json == null || json.isMissingNode() || json.isNull()) {
            refusals.add(new Refusal(List.of("body"), FIELD_REQUIRED));
        } else if (!json.isObject()) {
            refusals.add(new Refusal(List.of("body"), NOT_AN_OBJECT));
        } else {
            read = shape.apply(new Body(json, List.of("body"), refusals));
        }
        if (!refusals.isEmpty()) {
            throw new RequestRefused(refusals);
        }
        return read;
    }

    /**
     * The shape, built, unless something in the body has been refused; then null, and {@link #read}
     * refuses the body.
     */
    public <T> T build(Supplier<T> shape) {
        return refusals.isEmpty() ? shape.get() : null;
    }

    /** Refuse a field for a reason a shape found itself, such as a list that has to have something in it. */
    public void refuse(String name, String message) {
        refusals.add(new Refusal(at(name), message));
    }

    /**
     * A required string, between {@code min} and {@code max} characters long.
     *
     * @return the text, or null when it was refused
     */
    public String text(String name, int min, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return text(name, value, min, max);
    }

    /** A string that is {@code fallback} when absent, at most {@code max} characters long. */
    public String text(String name, String fallback, int max) {
        JsonNode value = object.get(name);
        return value == null ? fallback : text(name, value, 0, max);
    }

    /** A string that is {@code fallback} when absent. */
    public String text(String name, String fallback) {
        return text(name, fallback, Integer.MAX_VALUE);
    }

    /** A string that may be absent or null. */
    public Optional<String> optionalText(String name) {
        return optionalText(name, Integer.MAX_VALUE);
    }

    /** A string that may be absent or null, and is at most {@code max} characters long when sent. */
    public Optional<String> optionalText(String name, int max) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(text(name, value, 0, max));
    }

    /** A required ISO date, {@code 2024-10-03}. */
    public LocalDate date(String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return date(name, value);
    }

    /** An ISO date that may be absent or null. */
    public Optional<LocalDate> optionalDate(String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(date(name, value));
    }

    /**
     * A required moment: an ISO date and time of day such as {@code 2024-11-27T06:00:00Z}, with a
     * space allowed in place of the {@code T}. One written without an offset is read as UTC.
     */
    public OffsetDateTime moment(String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        if (value.isTextual()) {
            try {
                return momentOf(value.textValue());
            } catch (DateTimeParseException unreadable) {
                refuse(name, NOT_A_MOMENT);
                return null;
            }
        }
        refuse(name, NOT_A_MOMENT);
        return null;
    }

    /** A moment's text read, a space in place of the {@code T} taken as one. */
    private static OffsetDateTime momentOf(String text) {
        StringBuilder written = new StringBuilder(text);
        if (written.length() > 10 && written.charAt(10) == ' ') {
            written.setCharAt(10, 'T');
        }
        return OffsetDateTime.parse(written, MOMENT);
    }

    /** A required exact quantity. */
    public BigDecimal quantity(String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return quantity(name, value);
    }

    /** An exact quantity that is {@code fallback} when absent. A null sent on purpose is refused. */
    public BigDecimal quantity(String name, BigDecimal fallback) {
        JsonNode value = object.get(name);
        return value == null ? fallback : quantity(name, value);
    }

    /** An exact quantity that may be left out. A null sent on purpose is refused, as any non-number is. */
    public Optional<BigDecimal> optionalQuantity(String name) {
        JsonNode value = object.get(name);
        return value == null ? Optional.empty() : Optional.ofNullable(quantity(name, value));
    }

    /** An exact quantity that may be left out or sent as null, either of which means there is none. */
    public Optional<BigDecimal> nullableQuantity(String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(quantity(name, value));
    }

    /** One of an enumeration's values, required. */
    public <E extends Enum<E> & ValueEnum> E choice(String name, Class<E> type) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return choice(name, value, type);
    }

    /** One of an enumeration's values, {@code fallback} when absent. */
    public <E extends Enum<E> & ValueEnum> E choice(String name, Class<E> type, E fallback) {
        JsonNode value = object.get(name);
        return value == null ? fallback : choice(name, value, type);
    }

    /**
     * A yes or no, {@code fallback} when absent: JSON {@code true} or {@code false}, a {@code 1} or a
     * {@code 0}, or the words a form sends ({@code yes}, {@code off} and the like).
     */
    public boolean flag(String name, boolean fallback) {
        JsonNode value = object.get(name);
        if (value == null) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber() && (value.asLong() == 0 || value.asLong() == 1)) {
            return value.asLong() == 1;
        }
        if (value.isTextual()) {
            String word = value.textValue().strip().toLowerCase(Locale.ROOT);
            if (TRUE.contains(word)) {
                return true;
            }
            if (FALSE.contains(word)) {
                return false;
            }
        }
        refuse(name, NOT_A_FLAG);
        return fallback;
    }

    /** A required whole number from {@code min} to {@code max}, both included. */
    public Integer whole(String name, int min, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return whole(name, value, min, max);
    }

    /** A whole number from {@code min} to {@code max}, both included, {@code fallback} when absent. */
    public int whole(String name, int fallback, int min, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            return fallback;
        }
        Integer read = whole(name, value, min, max);
        return read == null ? fallback : read;
    }

    /** A required whole number above {@code floor}, which it may not equal, and up to {@code max}. */
    public Integer wholeAbove(String name, int floor, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        Integer read = whole(name, value, Integer.MIN_VALUE, max);
        if (read != null && read <= floor) {
            refuse(name, "Input should be greater than " + floor);
            return null;
        }
        return read;
    }

    /** A whole number that may be absent or null. */
    public Optional<Integer> optionalWhole(String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.ofNullable(whole(name, value, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    /** A list of objects, each read by {@code item}, that is empty when absent. */
    public <T> List<T> list(String name, Function<Body, T> item) {
        JsonNode value = object.get(name);
        return value == null ? List.of() : list(name, value, item, 0, Integer.MAX_VALUE);
    }

    /** A required list of objects, each read by {@code item}, of {@code min} to {@code max} items. */
    public <T> List<T> list(String name, Function<Body, T> item, int min, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            refuse(name, FIELD_REQUIRED);
            return null;
        }
        return list(name, value, item, min, max);
    }

    /** A list of strings that may be absent or null, either of which means there is none. */
    public Optional<List<String>> optionalTexts(String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isArray()) {
            refuse(name, NOT_A_LIST);
            return Optional.empty();
        }
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode element = value.get(index);
            if (element.isTextual()) {
                texts.add(element.textValue());
            } else {
                refusals.add(new Refusal(at(name, index), NOT_TEXT));
            }
        }
        return Optional.of(texts);
    }

    /**
     * An object of exact quantities under names the caller chooses, kept in the order sent; empty when
     * absent. Each quantity is read as {@link #quantity(String) a quantity field} is, and refused under
     * its own name.
     */
    public Map<String, BigDecimal> quantities(String name) {
        JsonNode value = object.get(name);
        Map<String, BigDecimal> read = new LinkedHashMap<>();
        if (value == null) {
            return read;
        }
        if (!value.isObject()) {
            refuse(name, NOT_A_DICTIONARY);
            return read;
        }
        Body entries = new Body(value, at(name), refusals);
        value.fieldNames().forEachRemaining(key -> {
            BigDecimal quantity = entries.quantity(key);
            if (quantity != null) {
                read.put(key, quantity);
            }
        });
        return read;
    }

    private List<Object> at(Object... steps) {
        List<Object> where = new ArrayList<>(location);
        where.addAll(Arrays.asList(steps));
        return where;
    }

    private String text(String name, JsonNode value, int min, int max) {
        if (!value.isTextual()) {
            refuse(name, NOT_TEXT);
            return null;
        }
        String text = value.textValue();
        int length = text.codePointCount(0, text.length());
        if (length < min) {
            refuse(name, "String should have at least " + min + " character" + (min == 1 ? "" : "s"));
            return null;
        }
        if (length > max) {
            refuse(name, "String should have at most " + max + " character" + (max == 1 ? "" : "s"));
            return null;
        }
        return text;
    }

    private LocalDate date(String name, JsonNode value) {
        if (value.isTextual()) {
            try {
                return LocalDate.parse(value.textValue());
            } catch (DateTimeParseException unreadable) {
                refuse(name, NOT_A_DATE);
                return null;
            }
        }
        refuse(name, NOT_A_DATE);
        return null;
    }

    private BigDecimal quantity(String name, JsonNode value) {
        if (value.isIntegralNumber()) {
            return new BigDecimal(value.bigIntegerValue());
        }
        if (value.isNumber()) {
            refuse(name, NOT_AS_A_STRING);
            return null;
        }
        if (value.isTextual()) {
            try {
                return Decimals.parse(value.textValue());
            } catch (NumberFormatException unreadable) {
                refuse(name, "Value error, " + Quoting.quoted(value.textValue()) + " is not a number");
                return null;
            }
        }
        refuse(name, "Value error, " + shown(value) + " is not a number");
        return null;
    }

    private <E extends Enum<E> & ValueEnum> E choice(String name, JsonNode value, Class<E> type) {
        if (value.isTextual() && ValueEnum.isValue(type, value.textValue())) {
            return ValueEnum.of(type, value.textValue());
        }
        refuse(name, "Input should be " + choices(type));
        return null;
    }

    private Integer whole(String name, JsonNode value, int min, int max) {
        BigDecimal read = wholeOf(value);
        if (read == null) {
            refuse(name, NOT_WHOLE);
            return null;
        }
        if (read.compareTo(BigDecimal.valueOf(min)) < 0) {
            refuse(name, "Input should be greater than or equal to " + min);
            return null;
        }
        if (read.compareTo(BigDecimal.valueOf(max)) > 0) {
            refuse(name, "Input should be less than or equal to " + max);
            return null;
        }
        return read.intValueExact();
    }

    /** A JSON number or numeric text with nothing after the point, or null for anything else. */
    private static BigDecimal wholeOf(JsonNode value) {
        BigDecimal number;
        if (value.isNumber()) {
            number = value.decimalValue();
        } else if (value.isTextual() && value.textValue().strip().matches("[+-]?\\d+")) {
            number = new BigDecimal(value.textValue().strip());
        } else {
            return null;
        }
        return number.stripTrailingZeros().scale() <= 0 ? number : null;
    }

    private <T> List<T> list(String name, JsonNode value, Function<Body, T> item, int min, int max) {
        if (!value.isArray()) {
            refuse(name, NOT_A_LIST);
            return null;
        }
        List<T> items = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode element = value.get(index);
            if (element.isObject()) {
                items.add(item.apply(new Body(element, at(name, index), refusals)));
            } else {
                refusals.add(new Refusal(at(name, index), NOT_AN_OBJECT));
            }
        }
        if (value.size() < min) {
            refuse(
                    name,
                    "List should have at least " + min + " item" + (min == 1 ? "" : "s") + " after validation, not "
                            + value.size());
            return null;
        }
        if (value.size() > max) {
            refuse(name, "List should have at most " + max + " items after validation, not " + value.size());
            return null;
        }
        return items;
    }

    /** {@code 'a', 'b' or 'c'}: the values a caller may send. */
    private static <E extends Enum<E> & ValueEnum> String choices(Class<E> type) {
        List<String> quoted = Arrays.stream(type.getEnumConstants())
                .map(member -> "'" + member.value() + "'")
                .toList();
        if (quoted.size() == 1) {
            return quoted.get(0);
        }
        return quoted.subList(0, quoted.size() - 1).stream().collect(Collectors.joining(", ")) + " or "
                + quoted.get(quoted.size() - 1);
    }

    /** A value that is not a number, as a refusal names it. */
    private static String shown(JsonNode value) {
        if (value.isNull()) {
            return "None";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "True" : "False";
        }
        return value.toString();
    }
}
