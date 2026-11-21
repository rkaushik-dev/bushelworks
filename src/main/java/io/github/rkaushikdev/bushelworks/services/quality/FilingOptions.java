package io.github.rkaushikdev.bushelworks.services.quality;

import java.util.Objects;
import java.util.Optional;

/**
 * What a complaint can name beyond who rang, when and about what.
 *
 * <pre>{@code
 * Quality.fileComplaint(uow, "C-2024-018", Source.BUYER, subject, raisedOn, deliveredOn,
 *         FilingOptions.defaults().withPartyCode("CARGILL").withBinId("BIN-3"));
 * }</pre>
 *
 * @param partyCode who it is, when known
 * @param binId the bin the grain came out of, when known
 * @param ticketNumber the scale ticket, when there is one; it is checked against the ticket file
 * @param saleNumber the sale, when there is one
 * @param probeId the retained sample, when there is one
 */
public record FilingOptions(
        Optional<String> partyCode,
        Optional<String> binId,
        Optional<String> ticketNumber,
        Optional<String> saleNumber,
        Optional<String> probeId) {

    public FilingOptions {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(binId, "binId");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(saleNumber, "saleNumber");
        Objects.requireNonNull(probeId, "probeId");
    }

    /** Nobody named, no bin, no ticket, no sale and no sample. */
    public static FilingOptions defaults() {
        return new FilingOptions(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Null clears it. */
    public FilingOptions withPartyCode(String value) {
        return new FilingOptions(Optional.ofNullable(value), binId, ticketNumber, saleNumber, probeId);
    }

    /** Null clears it. */
    public FilingOptions withBinId(String value) {
        return new FilingOptions(partyCode, Optional.ofNullable(value), ticketNumber, saleNumber, probeId);
    }

    /** Null clears it. */
    public FilingOptions withTicketNumber(String value) {
        return new FilingOptions(partyCode, binId, Optional.ofNullable(value), saleNumber, probeId);
    }

    /** Null clears it. */
    public FilingOptions withSaleNumber(String value) {
        return new FilingOptions(partyCode, binId, ticketNumber, Optional.ofNullable(value), probeId);
    }

    /** Null clears it. */
    public FilingOptions withProbeId(String value) {
        return new FilingOptions(partyCode, binId, ticketNumber, saleNumber, Optional.ofNullable(value));
    }
}
