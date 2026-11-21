package io.github.rkaushikdev.bushelworks.services.legacy;

import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One ticket from the old system's file, as far as it could be read.
 *
 * @param lineNumber the line it was on, from one
 * @param ticketNumber the ticket's number
 * @param scaleDate the day it crossed the scale
 * @param partyCode the producer, mapped to the new code when a map was given
 * @param commodityCode what was on the truck
 * @param locationCode the site
 * @param grossPounds the loaded weight
 * @param tarePounds the empty weight
 * @param disposition what happened to the grain
 * @param voided whether the old system had voided it
 * @param moisture the moisture reading, when the load was tested
 * @param testWeight the test weight reading, when the load was tested
 * @param damage the damage reading, when the load was tested
 */
public record LegacyRow(
        int lineNumber,
        String ticketNumber,
        LocalDate scaleDate,
        String partyCode,
        String commodityCode,
        String locationCode,
        BigDecimal grossPounds,
        BigDecimal tarePounds,
        Disposition disposition,
        boolean voided,
        Optional<BigDecimal> moisture,
        Optional<BigDecimal> testWeight,
        Optional<BigDecimal> damage) {

    public LegacyRow {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(scaleDate, "scaleDate");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(grossPounds, "grossPounds");
        Objects.requireNonNull(tarePounds, "tarePounds");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(moisture, "moisture");
        Objects.requireNonNull(testWeight, "testWeight");
        Objects.requireNonNull(damage, "damage");
    }

    /** Gross less tare. */
    public BigDecimal netPounds() {
        return grossPounds.subtract(tarePounds);
    }
}
