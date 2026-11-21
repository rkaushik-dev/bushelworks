package io.github.rkaushikdev.bushelworks.api.schemas;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.bushelworks.api.Json;
import io.github.rkaushikdev.bushelworks.api.Refusal;
import io.github.rkaushikdev.bushelworks.api.RequestRefused;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The reader every request shape is built on: what each kind of field takes, and what it refuses. */
class BodyTest {

    /** Everything a shape read, or the refusals it was refused with. */
    record Read(Object value) {}

    /** What a shape read out of {@code body}. */
    static Object read(Map<String, Object> body, Function<Body, ?> shape) {
        return Body.read(Json.tree(body), shape);
    }

    static List<String> refused(Object body, Function<Body, ?> shape) {
        RequestRefused refused = catchThrowableOfType(RequestRefused.class, () -> Body.read(Json.tree(body), shape));
        assertThat(refused).as("the body should have been refused").isNotNull();
        return refused.refusals().stream()
                .map(refusal -> refusal.field() + ": " + refusal.message())
                .toList();
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefusedWhole() {
        assertThat(refused(List.of(1), in -> in.text("x", 1, 2)))
                .containsExactly("body: Input should be a valid dictionary or object to extract fields from");
        assertThat(refused(null, in -> in.text("x", 1, 2))).containsExactly("body: Field required");
    }

    @Test
    void everyRefusedFieldIsReportedInTheOrderItWasRead() {
        Function<Body, Read> shape = in -> {
            String code = in.text("code", 1, 3);
            LocalDate on = in.date("on");
            BigDecimal bushels = in.quantity("bushels");
            return in.build(() -> new Read(List.of(code, on, bushels)));
        };
        assertThat(refused(object("code", "TOOLONG", "on", "03/10/2024", "bushels", 1.5), shape))
                .containsExactly(
                        "code: String should have at most 3 characters",
                        "on: " + Body.NOT_A_DATE,
                        "bushels: " + Body.NOT_AS_A_STRING);
        assertThat(read(object("code", "ABC", "on", "2024-10-03", "bushels", "12.50"), shape))
                .isEqualTo(new Read(List.of("ABC", LocalDate.of(2024, 10, 3), d("12.50"))));
    }

    @Test
    void textIsCountedInCharactersAndHasToBeText() {
        assertThat(read(object("name", "Möller"), in -> in.text("name", 1, 6))).isEqualTo("Möller");
        assertThat(refused(object("name", 7), in -> in.text("name", 1, 6)))
                .containsExactly("name: Input should be a valid string");
        assertThat(refused(object("name", ""), in -> in.text("name", 2, 6)))
                .containsExactly("name: String should have at least 2 characters");
        assertThat(read(object(), in -> in.text("name", "KS", 2))).isEqualTo("KS");
        assertThat(read(object("name", null), in -> in.optionalText("name"))).isEqualTo(Optional.empty());
    }

    @Test
    void aQuantityIsAStringOrAWholeNumber() {
        assertThat(read(object("q", " 1_000.25 "), in -> in.quantity("q"))).isEqualTo(d("1000.25"));
        assertThat(read(object("q", 4000), in -> in.quantity("q"))).isEqualTo(d("4000"));
        assertThat(read(object(), in -> in.quantity("q", BigDecimal.ONE))).isEqualTo(BigDecimal.ONE);
        assertThat(read(object(), in -> in.optionalQuantity("q"))).isEqualTo(Optional.empty());
        assertThat(refused(object("q", "heavy"), in -> in.quantity("q")))
                .containsExactly("q: Value error, 'heavy' is not a number");
        assertThat(refused(object("q", null), in -> in.optionalQuantity("q")))
                .containsExactly("q: Value error, None is not a number");
        assertThat(refused(object("q", true), in -> in.quantity("q")))
                .containsExactly("q: Value error, True is not a number");
    }

    @Test
    void aChoiceIsOneOfTheValues() {
        assertThat(read(object("role", "tenant"), in -> in.choice("role", SplitRole.class)))
                .isSameAs(SplitRole.TENANT);
        assertThat(read(object(), in -> in.choice("role", SplitRole.class, SplitRole.OWNER)))
                .isSameAs(SplitRole.OWNER);
        assertThat(refused(object("role", "TENANT"), in -> in.choice("role", SplitRole.class)))
                .containsExactly("role: Input should be 'owner', 'landlord', 'tenant' or 'lienholder'");
    }

    @Test
    void aFlagTakesTheWordsAFormSends() {
        assertThat(read(object("f", "yes"), in -> in.flag("f", false))).isEqualTo(true);
        assertThat(read(object("f", 0), in -> in.flag("f", true))).isEqualTo(false);
        assertThat(read(object(), in -> in.flag("f", true))).isEqualTo(true);
        assertThat(refused(object("f", "maybe"), in -> in.flag("f", false)))
                .containsExactly("f: Input should be a valid boolean");
    }

    @Test
    void aWholeNumberIsHeldToItsBounds() {
        assertThat(read(object("n", "12"), in -> in.whole("n", 1, 20))).isEqualTo(12);
        assertThat(read(object("n", 3.0), in -> in.whole("n", 1, 20))).isEqualTo(3);
        assertThat(read(object(), in -> in.whole("n", 25, 1, 100))).isEqualTo(25);
        assertThat(read(object(), in -> in.optionalWhole("n"))).isEqualTo(Optional.empty());
        assertThat(refused(object("n", 0), in -> in.whole("n", 1, 20)))
                .containsExactly("n: Input should be greater than or equal to 1");
        assertThat(refused(object("n", 21), in -> in.whole("n", 1, 20)))
                .containsExactly("n: Input should be less than or equal to 20");
        assertThat(refused(object("n", 2.5), in -> in.whole("n", 1, 20)))
                .containsExactly("n: Input should be a valid integer");
    }

    @Test
    void aListNamesEachItemByItsPlace() {
        Function<Body, List<SplitIn>> shape = in -> in.list("shares", SplitIn::read, 1, 2);
        assertThat(refused(object("shares", list(object("percent", "50"), "x")), shape))
                .containsExactly(
                        "shares.0.party_code: Field required",
                        "shares.1: Input should be a valid dictionary or object to extract fields from");
        assertThat(refused(object("shares", list()), shape))
                .containsExactly("shares: List should have at least 1 item after validation, not 0");
        assertThat(refused(object("shares", "all of it"), shape))
                .containsExactly("shares: Input should be a valid list");
        assertThat(read(object(), in -> in.list("shares", SplitIn::read))).isEqualTo(List.of());
    }

    @Test
    void aRefusalNamesItsPlaceUnderTheBody() {
        RequestRefused refused =
                catchThrowableOfType(RequestRefused.class, () -> Body.read(Json.tree(object()), in -> in.date("on")));
        assertThat(refused.refusals()).containsExactly(new Refusal(List.of("body", "on"), Body.FIELD_REQUIRED));
        assertThat(refused).hasMessage("on: Field required");
    }
}
