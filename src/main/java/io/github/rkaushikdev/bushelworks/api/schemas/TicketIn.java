package io.github.rkaushikdev.bushelworks.api.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A load as the scale house sends it.
 *
 * <p>The weights are exact decimals sent as strings. Everything after the disposition may be left
 * out: dockage is none, the contract is none, the truck, driver, farm, field and remarks are empty,
 * and the sample is empty. The split may not be: a ticket needs at least one share.
 *
 * @param ticketNumber the ticket number, one to 24 characters
 * @param locationCode the site, one to 12 characters
 * @param scaleDate the day the load crossed the scale
 * @param commodityCode the commodity, one to eight characters
 * @param partyCode the producer the ticket is filed under, one to 24 characters
 * @param grossPounds the loaded truck
 * @param tarePounds the empty truck
 * @param disposition what the producer asked for at the window
 * @param dockagePercent the percent the cleaner takes out
 * @param contractNumber the contract the driver named, if he named one
 * @param truckId the truck
 * @param driver the driver
 * @param farm the farm the load came from
 * @param fieldName the field
 * @param remarks anything the scale house wrote on it
 * @param splits who owns what share of the load
 * @param sample the grader's readings
 */
public record TicketIn(
        String ticketNumber,
        String locationCode,
        LocalDate scaleDate,
        String commodityCode,
        String partyCode,
        BigDecimal grossPounds,
        BigDecimal tarePounds,
        Disposition disposition,
        BigDecimal dockagePercent,
        Optional<String> contractNumber,
        String truckId,
        String driver,
        String farm,
        String fieldName,
        String remarks,
        List<SplitIn> splits,
        List<SampleReading> sample) {

    /** What a ticket with no split is refused with. */
    public static final String NEEDS_A_SHARE = "Value error, a ticket needs at least one share";

    public TicketIn {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(scaleDate, "scaleDate");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(contractNumber, "contractNumber");
        splits = List.copyOf(splits);
        sample = List.copyOf(sample);
    }

    /** A ticket as a caller sends it. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TicketIn from(JsonNode json) {
        return Body.read(json, TicketIn::read);
    }

    /** A ticket read out of a body, or out of one item of a batch. */
    public static TicketIn read(Body in) {
        String ticketNumber = in.text("ticket_number", 1, 24);
        String locationCode = in.text("location_code", 1, 12);
        LocalDate scaleDate = in.date("scale_date");
        String commodityCode = in.text("commodity_code", 1, 8);
        String partyCode = in.text("party_code", 1, 24);
        BigDecimal grossPounds = in.quantity("gross_pounds");
        BigDecimal tarePounds = in.quantity("tare_pounds");
        Disposition disposition = in.choice("disposition", Disposition.class);
        BigDecimal dockagePercent = in.quantity("dockage_percent", BigDecimal.ZERO);
        Optional<String> contractNumber = in.optionalText("contract_number");
        String truckId = in.text("truck_id", "");
        String driver = in.text("driver", "");
        String farm = in.text("farm", "");
        String fieldName = in.text("field_name", "");
        String remarks = in.text("remarks", "");
        List<SplitIn> splits = in.list("splits", SplitIn::read);
        if (splits != null && splits.isEmpty()) {
            in.refuse("splits", NEEDS_A_SHARE);
        }
        List<SampleReading> sample = in.list("sample", SampleReading::read);
        return in.build(() -> new TicketIn(
                ticketNumber,
                locationCode,
                scaleDate,
                commodityCode,
                partyCode,
                grossPounds,
                tarePounds,
                disposition,
                dockagePercent,
                contractNumber,
                truckId,
                driver,
                farm,
                fieldName,
                remarks,
                splits,
                sample));
    }
}
