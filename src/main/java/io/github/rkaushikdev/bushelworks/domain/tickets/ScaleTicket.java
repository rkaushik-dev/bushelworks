package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One load across the scale.
 *
 * <p>This is the document everything else refers back to. It records what came off the truck, who
 * it belongs to and what the producer wants done with it. It is immutable once written: a scale
 * house that lets a ticket be edited after the fact cannot answer the only question an auditor ever
 * asks, which is what the scale said at the time. Corrections are made by voiding and reissuing,
 * never by amendment.
 *
 * @param ticketNumber the ticket number
 * @param scaleDate the day the load crossed the scale
 * @param locationCode where
 * @param commodityCode what
 * @param grossPounds the loaded truck
 * @param tarePounds the empty truck
 * @param disposition what the producer asked for at the window
 * @param splits who owns what share of the load
 * @param sample the grader's readings, in the order they were taken
 * @param dockagePercent the percent the cleaner takes out
 * @param truckId the truck
 * @param driver the driver
 * @param farm the farm the load came from
 * @param fieldName the field
 * @param contractNumber the contract the driver named, if he named one
 * @param remarks anything the scale house wrote on it
 * @param voided whether this is the voided twin of a ticket
 */
public record ScaleTicket(
        String ticketNumber,
        LocalDate scaleDate,
        String locationCode,
        String commodityCode,
        BigDecimal grossPounds,
        BigDecimal tarePounds,
        Disposition disposition,
        List<SplitShare> splits,
        Map<Factor, BigDecimal> sample,
        BigDecimal dockagePercent,
        String truckId,
        String driver,
        String farm,
        String fieldName,
        Optional<String> contractNumber,
        String remarks,
        boolean voided) {

    public ScaleTicket {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(scaleDate, "scaleDate");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(grossPounds, "grossPounds");
        Objects.requireNonNull(tarePounds, "tarePounds");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(truckId, "truckId");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(farm, "farm");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(contractNumber, "contractNumber");
        Objects.requireNonNull(remarks, "remarks");
        splits = List.copyOf(splits);
        sample = Ordered.copyOf(sample);
        if (ticketNumber.isEmpty()) {
            throw new TicketError("a ticket needs a number");
        }
        if (splits.isEmpty()) {
            throw new TicketError(ticketNumber + ": a ticket needs at least one share");
        }
        if (dockagePercent.signum() < 0 || dockagePercent.compareTo(Decimals.HUNDRED) >= 0) {
            throw new TicketError(ticketNumber + ": dockage out of range");
        }
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SplitShare share : splits) {
            if (!seen.add(share.partyId())) {
                throw new TicketError(ticketNumber + ": " + share.partyId() + " appears twice on the split");
            }
            total = total.add(share.percent());
        }
        if (total.compareTo(Decimals.HUNDRED) != 0) {
            throw new TicketError(ticketNumber + ": shares total " + total + "%, they have to make 100%");
        }
        if (disposition == Disposition.CONTRACT
                && contractNumber.filter(n -> !n.isEmpty()).isEmpty()) {
            throw new TicketError(ticketNumber + ": a contract delivery needs a contract number");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** This ticket, ready to have a field or two changed. */
    public Builder toBuilder() {
        return new Builder()
                .ticketNumber(ticketNumber)
                .scaleDate(scaleDate)
                .locationCode(locationCode)
                .commodityCode(commodityCode)
                .grossPounds(grossPounds)
                .tarePounds(tarePounds)
                .disposition(disposition)
                .splits(splits)
                .sample(sample)
                .dockagePercent(dockagePercent)
                .truckId(truckId)
                .driver(driver)
                .farm(farm)
                .fieldName(fieldName)
                .contractNumber(contractNumber.orElse(null))
                .remarks(remarks)
                .voided(voided);
    }

    /** Gross less tare, before any dockage comes off. */
    public BigDecimal netPounds() {
        return Units.netPounds(grossPounds, tarePounds);
    }

    /** The material the cleaner takes out, which the producer is not paid for. */
    public BigDecimal dockagePounds() {
        if (dockagePercent.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return Units.percentOf(netPounds(), dockagePercent, Units.POUND_PLACES);
    }

    /** What is left after dockage. This is what becomes bushels. */
    public BigDecimal cleanPounds() {
        return Units.quantize(netPounds().subtract(dockagePounds()), Units.POUND_PLACES);
    }

    /** Bushels before dockage, which is what the driver watches on the board. */
    public BigDecimal grossBushels(Commodity commodity) {
        return Units.poundsToBushels(netPounds(), commodity.standardTestWeight());
    }

    /** Bushels after dockage and before moisture shrink. */
    public BigDecimal cleanBushels(Commodity commodity) {
        return Units.poundsToBushels(cleanPounds(), commodity.standardTestWeight());
    }

    public BigDecimal shareOf(String partyId) {
        for (SplitShare share : splits) {
            if (share.partyId().equals(partyId)) {
                return share.percent();
            }
        }
        throw new TicketError(ticketNumber + ": " + partyId + " has no share of this load");
    }

    /** The parties on the split, in split order. */
    public List<String> parties() {
        return splits.stream().map(SplitShare::partyId).toList();
    }

    public Optional<BigDecimal> factor(Factor factor) {
        return Optional.ofNullable(sample.get(factor));
    }

    public BigDecimal requireFactor(Factor factor) {
        return factor(factor)
                .orElseThrow(() -> new TicketError(ticketNumber + ": sample has no reading for " + factor.value()));
    }

    /**
     * The voided twin of this ticket.
     *
     * <p>Voiding does not erase anything. The ticket stays in the book and the void carries the
     * reason, because the state inspector reads both.
     */
    public ScaleTicket voidTicket(String reason) {
        if (voided) {
            throw new TicketError(ticketNumber + ": already voided");
        }
        if (reason.isEmpty()) {
            throw new TicketError(ticketNumber + ": a void needs a reason");
        }
        return toBuilder().remarks("VOID: " + reason).voided(true).build();
    }

    /** A ticket assembled a field at a time; text fields default to empty, dockage to none. */
    public static final class Builder {

        private String ticketNumber;
        private LocalDate scaleDate;
        private String locationCode;
        private String commodityCode;
        private BigDecimal grossPounds;
        private BigDecimal tarePounds;
        private Disposition disposition;
        private List<SplitShare> splits = List.of();
        private Map<Factor, BigDecimal> sample = Map.of();
        private BigDecimal dockagePercent = BigDecimal.ZERO;
        private String truckId = "";
        private String driver = "";
        private String farm = "";
        private String fieldName = "";
        private String contractNumber;
        private String remarks = "";
        private boolean voided;

        private Builder() {}

        public Builder ticketNumber(String value) {
            this.ticketNumber = value;
            return this;
        }

        public Builder scaleDate(LocalDate value) {
            this.scaleDate = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder grossPounds(BigDecimal value) {
            this.grossPounds = value;
            return this;
        }

        public Builder tarePounds(BigDecimal value) {
            this.tarePounds = value;
            return this;
        }

        public Builder disposition(Disposition value) {
            this.disposition = value;
            return this;
        }

        public Builder splits(List<SplitShare> value) {
            this.splits = value;
            return this;
        }

        public Builder sample(Map<Factor, BigDecimal> value) {
            this.sample = value;
            return this;
        }

        public Builder dockagePercent(BigDecimal value) {
            this.dockagePercent = value;
            return this;
        }

        public Builder truckId(String value) {
            this.truckId = value;
            return this;
        }

        public Builder driver(String value) {
            this.driver = value;
            return this;
        }

        public Builder farm(String value) {
            this.farm = value;
            return this;
        }

        public Builder fieldName(String value) {
            this.fieldName = value;
            return this;
        }

        /** The contract the driver named, or null for none. */
        public Builder contractNumber(String value) {
            this.contractNumber = value;
            return this;
        }

        public Builder remarks(String value) {
            this.remarks = value;
            return this;
        }

        public Builder voided(boolean value) {
            this.voided = value;
            return this;
        }

        public ScaleTicket build() {
            return new ScaleTicket(
                    ticketNumber,
                    scaleDate,
                    locationCode,
                    commodityCode,
                    grossPounds,
                    tarePounds,
                    disposition,
                    splits,
                    sample,
                    dockagePercent,
                    truckId,
                    driver,
                    farm,
                    fieldName,
                    Optional.ofNullable(contractNumber),
                    remarks,
                    voided);
        }
    }
}
