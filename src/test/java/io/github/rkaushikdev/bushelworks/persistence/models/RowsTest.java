package io.github.rkaushikdev.bushelworks.persistence.models;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every column a row carries reads back as it was set, and a column that may be empty reads back
 * empty when it is.
 *
 * <p>The rows are plain holders, so this goes through each of them the same way: every setter is
 * handed a value of its own, and the getter for the same column has to give that value back.
 */
class RowsTest {

    static Stream<Class<?>> rows() {
        return TestDatabase.fresh().entityManagerFactory().getMetamodel().getEntities().stream()
                .<Class<?>>map(EntityType::getJavaType)
                .sorted((one, other) -> one.getSimpleName().compareTo(other.getSimpleName()));
    }

    /** A value of {@code type} that no default starts out with, different for each column. */
    private static Object sample(Class<?> type, int seed) {
        if (type == String.class) {
            return "v" + seed;
        }
        if (type == Integer.class || type == int.class) {
            return 1_000 + seed;
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(seed + ".25");
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2024, 1, 1).plusDays(seed);
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.of(2024, 1, 1, 6, 0, 0, 0, ZoneOffset.UTC).plusHours(seed);
        }
        throw new IllegalArgumentException("no sample for " + type);
    }

    private static Method getter(Class<?> row, Field field) throws NoSuchMethodException {
        String name = Character.toUpperCase(field.getName().charAt(0))
                + field.getName().substring(1);
        String prefix = field.getType() == boolean.class ? "is" : "get";
        return row.getMethod(prefix + name);
    }

    private static Object read(Object row, Method getter) throws ReflectiveOperationException {
        Object value = getter.invoke(row);
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    /** The column fields of a row and the rows it extends, each with its setter. */
    private static List<Field> columns(Class<?> row) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = row; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Column.class) && !Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyColumnReadsBackAsItWasSet(Class<?> type) throws ReflectiveOperationException {
        assertThat(type.getAnnotation(Table.class)).as("names its table").isNotNull();
        Object row = type.getConstructor().newInstance();
        int seed = 0;
        int checked = 0;
        for (Field field : columns(type)) {
            String name = Character.toUpperCase(field.getName().charAt(0))
                    + field.getName().substring(1);
            Optional<Method> setter = Stream.of(type.getMethods())
                    .filter(method -> method.getName().equals("set" + name) && method.getParameterCount() == 1)
                    .findFirst();
            if (setter.isEmpty()) {
                // The key and the timestamps are the database's to set, not the caller's.
                assertThat(field.getName()).isIn("id", "createdAt", "updatedAt");
                continue;
            }
            Method getter = getter(type, field);
            Class<?> parameter = setter.get().getParameterTypes()[0];
            // A flag is turned the other way from its default, so the read can tell the two apart.
            Object value = parameter == boolean.class ? !((Boolean) getter.invoke(row)) : sample(parameter, ++seed);
            setter.get().invoke(row, value);
            assertThat(read(row, getter))
                    .as(type.getSimpleName() + "." + field.getName())
                    .isEqualTo(value);
            checked++;
        }
        assertThat(checked).as("columns checked").isPositive();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aColumnThatMayBeEmptyReadsBackEmpty(Class<?> type) throws ReflectiveOperationException {
        Object row = type.getConstructor().newInstance();
        for (Field field : columns(type)) {
            Method getter = getter(type, field);
            if (getter.getReturnType() != Optional.class) {
                assertThat(field.getAnnotation(Column.class).nullable())
                        .as(type.getSimpleName() + "." + field.getName() + " may not be empty")
                        .isFalse();
                continue;
            }
            assertThat(field.getAnnotation(Column.class).nullable()).isTrue();
            String name = Character.toUpperCase(field.getName().charAt(0))
                    + field.getName().substring(1);
            Method setter = type.getMethod("set" + name, field.getType());
            setter.invoke(row, sample(field.getType(), 1));
            assertThat(getter.invoke(row)).isNotEqualTo(Optional.empty());
            try {
                setter.invoke(row, (Object) null);
            } catch (InvocationTargetException refused) {
                throw new AssertionError(field.getName() + " refused an empty value", refused.getCause());
            }
            assertThat(getter.invoke(row))
                    .as(type.getSimpleName() + "." + field.getName())
                    .isEqualTo(Optional.empty());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aRowNotYetWrittenHasNoKey(Class<?> type) throws ReflectiveOperationException {
        Row row = (Row) type.getConstructor().newInstance();
        assertThat(row.getId()).isNull();
        assertThat(row.describe()).isEqualTo("<" + type.getSimpleName() + " id=null>");
    }
}
