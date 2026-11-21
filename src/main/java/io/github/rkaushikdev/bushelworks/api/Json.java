package io.github.rkaushikdev.bushelworks.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * How the service writes JSON, in one place so the application and the tests of its shapes cannot
 * drift apart.
 *
 * <p>Field names are snake case and dates are ISO strings. Every decimal crosses the wire as a
 * string, never as a JSON number: a bushel count with four places and a price with four places both
 * survive a round trip through a double, but their product does not, and the first symptom is a
 * settlement a cent out on one statement in three hundred. Enumerations are written as the short
 * values the database stores ({@code cash_sale}, {@code test_weight}).
 *
 * <p>Request bodies are not read through these rules field by field: each request shape reads its
 * own fields with {@link io.github.rkaushikdev.bushelworks.api.schemas.Body}, so that every field a
 * caller got wrong is reported at once.
 */
public final class Json {

    private Json() {}

    /** Apply the house's JSON rules to a builder: the service's, or a test's. */
    public static void configure(Jackson2ObjectMapperBuilder builder) {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modulesToInstall(module());
    }

    /** A mapper built the way the service builds its own. */
    public static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        configure(builder);
        return builder.build();
    }

    /** A value as the tree a request body would have arrived as. */
    public static JsonNode tree(Object value) {
        return mapper().valueToTree(value);
    }

    /** Decimals as their text, and value enumerations as their values. */
    static SimpleModule module() {
        SimpleModule module = new SimpleModule("bushelworks");
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        module.addSerializer(ValueEnum.class, new ValueEnumWriter());
        return module;
    }

    /** Writes any value enumeration as its value. */
    private static final class ValueEnumWriter extends JsonSerializer<ValueEnum> {

        @Override
        public void serialize(ValueEnum member, JsonGenerator out, SerializerProvider provider) throws IOException {
            out.writeString(member.value());
        }
    }
}
