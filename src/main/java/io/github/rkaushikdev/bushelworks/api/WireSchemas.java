package io.github.rkaushikdev.bushelworks.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.bushelworks.ValueEnum;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.Iterator;

/**
 * How the API document describes the shapes: as the service writes them, not as their Java types
 * look.
 *
 * <p>Field names come out snake case, because the resolver reads the shapes through the service's
 * own JSON rules. A decimal is described as a string, since that is how it crosses the wire, and an
 * enumeration as the values it is written as ({@code cash_sale}, not {@code CASH_SALE}). The
 * document is OpenAPI 3.1, which {@code springdoc.api-docs.version} fixes.
 */
public final class WireSchemas extends ModelResolver {

    /**
     * A resolver reading the shapes through {@code json}'s rules. The resolver adds its own
     * annotation reading to the mapper it is given, so it is handed a copy.
     */
    public WireSchemas(ObjectMapper json) {
        super(json.copy());
        setOpenapi31(true);
    }

    @Override
    @SuppressWarnings("rawtypes") // the converter interface declares the raw schema type
    public Schema resolve(AnnotatedType annotated, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType type = _mapper.constructType(annotated.getType());
        Class<?> raw = type.getRawClass();
        if (raw == BigDecimal.class) {
            JsonSchema decimal = text();
            decimal.setFormat("decimal");
            return decimal;
        }
        if (raw.isEnum() && ValueEnum.class.isAssignableFrom(raw)) {
            JsonSchema values = text();
            for (Object member : raw.getEnumConstants()) {
                values.addEnumItemObject(((ValueEnum) member).value());
            }
            return values;
        }
        return super.resolve(annotated, context, chain);
    }

    private static JsonSchema text() {
        JsonSchema text = new JsonSchema();
        text.addType("string");
        return text;
    }
}
