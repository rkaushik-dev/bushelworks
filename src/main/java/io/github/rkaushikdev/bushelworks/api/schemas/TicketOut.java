package io.github.rkaushikdev.bushelworks.api.schemas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A ticket as it stands on file, read back.
 *
 * @param ticketNumber the ticket number
 * @param locationCode the site
 * @param scaleDate the day the load crossed the scale
 * @param commodityCode the commodity
 * @param partyCode the producer it is filed under
 * @param grossPounds the loaded truck
 * @param tarePounds the empty truck
 * @param netPounds gross less tare
 * @param dockagePercent the percent the cleaner took out
 * @param disposition what the producer asked for, as its value
 * @param contractNumber the contract named on it, if any
 * @param voided whether it has been voided
 * @param settled whether it has been settled
 * @param sample the grader's readings, by factor
 * @param splits who owns what share of the load
 */
public record TicketOut(
        String ticketNumber,
        String locationCode,
        LocalDate scaleDate,
        String commodityCode,
        String partyCode,
        BigDecimal grossPounds,
        BigDecimal tarePounds,
        BigDecimal netPounds,
        BigDecimal dockagePercent,
        String disposition,
        Optional<String> contractNumber,
        boolean voided,
        boolean settled,
        List<SampleReading> sample,
        List<SplitIn> splits) {

    public TicketOut {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(contractNumber, "contractNumber");
        sample = List.copyOf(sample);
        splits = List.copyOf(splits);
    }
}
