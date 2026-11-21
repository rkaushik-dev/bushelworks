package io.github.rkaushikdev.bushelworks.api.schemas;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Json;
import io.github.rkaushikdev.bushelworks.api.RequestRefused;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The request and response shapes, read and written the way the service reads and writes them. */
class ApiSchemasTest {

    static Map<String, Object> aBody(Object... overrides) {
        Map<String, Object> body = object(
                "ticket_number", "A-10441",
                "location_code", "HOME",
                "scale_date", "2024-10-03",
                "commodity_code", "CORN",
                "party_code", "MERRICK",
                "gross_pounds", "78420",
                "tare_pounds", "26180",
                "disposition", "cash_sale",
                "splits", list(object("party_code", "MERRICK", "percent", "100")),
                "sample",
                        list(
                                object("factor", "moisture", "value", "18.2"),
                                object("factor", "test_weight", "value", "56.5")));
        return with(body, overrides);
    }

    static TicketIn ticket(Map<String, Object> body) {
        return TicketIn.from(Json.tree(body));
    }

    static JsonNode written(Object shape) {
        return Json.mapper().valueToTree(shape);
    }

    @Test
    void aTicketBodyParsesIntoDecimals() {
        TicketIn ticket = ticket(aBody());
        assertThat(ticket.grossPounds()).isEqualTo(d("78420"));
        assertThat(ticket.scaleDate()).isEqualTo(LocalDate.of(2024, 10, 3));
        assertThat(ticket.disposition()).isSameAs(Disposition.CASH_SALE);
        assertThat(ticket.sample().get(0).factor()).isSameAs(Factor.MOISTURE);
    }

    @Test
    void aFloatQuantityIsRefusedWithAnExplanation() {
        assertThatThrownBy(() -> ticket(aBody("gross_pounds", 78420.5)))
                .isInstanceOf(RequestRefused.class)
                .hasMessageContaining("as strings");
    }

    @Test
    void aQuantityThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> ticket(aBody("gross_pounds", "heavy"))).isInstanceOf(RequestRefused.class);
    }

    @Test
    void aTicketNeedsAtLeastOneShare() {
        assertThatThrownBy(() -> ticket(aBody("splits", list()))).isInstanceOf(RequestRefused.class);
    }

    @Test
    void anUnknownFactorIsRefused() {
        assertThatThrownBy(() -> SampleReading.from(Json.tree(object("factor", "colour", "value", "1"))))
                .isInstanceOf(RequestRefused.class);
    }

    @Test
    void anUnknownDispositionIsRefused() {
        assertThatThrownBy(() -> ticket(aBody("disposition", "borrowed"))).isInstanceOf(RequestRefused.class);
    }

    @Test
    void aShareDefaultsToOwner() {
        SplitIn share = SplitIn.from(Json.tree(object("party_code", "MERRICK", "percent", "100")));
        assertThat(share.role()).isSameAs(SplitRole.OWNER);
    }

    @Test
    void aRoleCanBeNamed() {
        SplitIn share = SplitIn.from(Json.tree(object("party_code", "BROCKMAN", "percent", "25", "role", "landlord")));
        assertThat(share.role()).isSameAs(SplitRole.LANDLORD);
    }

    @Test
    void decimalsSerialiseAsStrings() {
        SettlementLineOut line = new SettlementLineOut(
                "gross", "GRAIN", "corn", d("3904.70"), Optional.of(d("897.6324")), Optional.of(d("4.3500")));
        JsonNode body = written(line);
        assertThat(body.get("dollars").isTextual()).isTrue();
        assertThat(body.get("dollars").asText()).isEqualTo("3904.70");
        assertThat(body.get("quantity").asText()).isEqualTo("897.6324");
        assertThat(body.get("rate").asText()).isEqualTo("4.3500");
    }

    @Test
    void anAbsentOptionalDecimalStaysNull() {
        JsonNode body = written(SettlementLineOut.flat("drying", "DRY", "drying", d("102.33")));
        assertThat(body.has("quantity")).isTrue();
        assertThat(body.get("quantity").isNull()).isTrue();
    }

    @Test
    void aPageKnowsWhetherThereIsMore() {
        assertThat(new Page<>(List.of(1, 2), 5, 0, 2).hasMore()).isTrue();
        assertThat(new Page<>(List.of(1, 2), 2, 0, 2).hasMore()).isFalse();
        assertThat(new Page<>(List.of(), 0, 0, 2).hasMore()).isFalse();
    }

    @Test
    void aProblemBodyHasACodeAndASentence() {
        Map<String, Object> body = Json.mapper()
                .convertValue(
                        Problem.of("tare_exceeds_gross", "tare exceeds gross"),
                        new TypeReference<Map<String, Object>>() {});
        Map<String, Object> expected = new HashMap<>();
        expected.put("code", "tare_exceeds_gross");
        expected.put("detail", "tare exceeds gross");
        expected.put("request_id", null);
        assertThat(body).isEqualTo(expected);
    }

    @Test
    void optionalTicketFieldsDefaultToEmpty() {
        TicketIn ticket = ticket(aBody());
        assertThat(ticket.truckId()).isEmpty();
        assertThat(ticket.contractNumber()).isEmpty();
        assertThat(ticket.dockagePercent()).isEqualByComparingTo(d("0"));
    }

    @Test
    void aBlankTicketNumberIsRefused() {
        assertThatThrownBy(() -> ticket(aBody("ticket_number", ""))).isInstanceOf(RequestRefused.class);
    }
}
