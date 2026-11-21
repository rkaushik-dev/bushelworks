package io.github.rkaushikdev.bushelworks.persistence;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.domain.sales.Carrier;
import io.github.rkaushikdev.bushelworks.domain.sales.Destination;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleContract;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleKind;
import io.github.rkaushikdev.bushelworks.domain.sales.Specification;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SampleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementLineRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.persistence.models.TicketSplitRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rows in, value objects out, and back.
 *
 * <p>The domain objects check themselves when they are built, so reading one from a row is also a
 * check that the row is still legal. That is on purpose. A row written before a constraint existed
 * fails loudly here rather than settling quietly at the wrong number.
 *
 * <p>Nothing here writes to a session. Persisting is the repository's job; this only translates.
 */
public final class Mappers {

    private Mappers() {}

    /**
     * The probe readings as a sample, in the order the rows come.
     *
     * @throws MappingError naming a factor the grade ladder does not know
     */
    public static Map<Factor, BigDecimal> sampleFromRows(List<SampleRow> rows) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        for (SampleRow row : rows) {
            Factor factor;
            try {
                factor = Factor.of(row.getFactor());
            } catch (IllegalArgumentException unknown) {
                throw new MappingError(row.getFactor() + " is not a grade factor", unknown);
            }
            sample.put(factor, row.getValue());
        }
        return sample;
    }

    /** One row per reading, in factor order, so the same sample is always stored the same way. */
    public static List<SampleRow> sampleToRows(Map<Factor, BigDecimal> sample) {
        return sample.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Factor.BY_VALUE))
                .map(reading -> {
                    SampleRow row = new SampleRow();
                    row.setFactor(reading.getKey().value());
                    row.setValue(reading.getValue());
                    return row;
                })
                .toList();
    }

    public static List<SplitShare> splitsFromRows(List<TicketSplitRow> rows) {
        return rows.stream()
                .map(row -> new SplitShare(row.getPartyCode(), row.getPercent(), SplitRole.of(row.getRole())))
                .toList();
    }

    public static List<TicketSplitRow> splitsToRows(List<SplitShare> shares) {
        return shares.stream()
                .map(share -> {
                    TicketSplitRow row = new TicketSplitRow();
                    row.setPartyCode(share.partyId());
                    row.setPercent(share.percent());
                    row.setRole(share.role().value());
                    return row;
                })
                .toList();
    }

    /** The ticket a row records, its shares and readings with it. */
    public static ScaleTicket ticketFromRow(ScaleTicketRow row) {
        return ScaleTicket.builder()
                .ticketNumber(row.getTicketNumber())
                .scaleDate(row.getScaleDate())
                .locationCode(row.getLocationCode())
                .commodityCode(row.getCommodityCode())
                .grossPounds(row.getGrossPounds())
                .tarePounds(row.getTarePounds())
                .disposition(Disposition.of(row.getDisposition()))
                .splits(splitsFromRows(row.getSplits()))
                .sample(sampleFromRows(row.getSamples()))
                .dockagePercent(row.getDockagePercent())
                .truckId(row.getTruckId().orElse(""))
                .driver(row.getDriver().orElse(""))
                .farm(row.getFarm().orElse(""))
                .fieldName(row.getFieldName().orElse(""))
                .contractNumber(row.getContractNumber().orElse(null))
                .remarks(row.getRemarks().orElse(""))
                .voided(row.isVoided())
                .build();
    }

    /** A new row for {@code ticket}, filed under {@code partyCode}. Empty text is stored as none. */
    public static ScaleTicketRow ticketToRow(ScaleTicket ticket, String partyCode) {
        ScaleTicketRow row = new ScaleTicketRow();
        row.setTicketNumber(ticket.ticketNumber());
        row.setLocationCode(ticket.locationCode());
        row.setScaleDate(ticket.scaleDate());
        row.setCommodityCode(ticket.commodityCode());
        row.setPartyCode(partyCode);
        row.setGrossPounds(ticket.grossPounds());
        row.setTarePounds(ticket.tarePounds());
        row.setDockagePercent(ticket.dockagePercent());
        row.setDisposition(ticket.disposition().value());
        row.setContractNumber(ticket.contractNumber().orElse(null));
        row.setTruckId(noneIfEmpty(ticket.truckId()));
        row.setDriver(noneIfEmpty(ticket.driver()));
        row.setFarm(noneIfEmpty(ticket.farm()));
        row.setFieldName(noneIfEmpty(ticket.fieldName()));
        row.setRemarks(noneIfEmpty(ticket.remarks()));
        row.setVoided(ticket.voided());
        row.setSamples(sampleToRows(ticket.sample()));
        row.setSplits(splitsToRows(ticket.splits()));
        return row;
    }

    /**
     * Read {@code CORNZ24} back into a month.
     *
     * <p>The symbol is stored rather than three columns because it is what the hedge desk types and
     * what the broker statement shows. Reading it back needs the commodity because the code is a
     * prefix of no fixed length.
     *
     * @param symbol the stored symbol; null or empty reads as no month
     * @throws MappingError when the symbol is for another commodity or has no month in it
     */
    public static Optional<FuturesMonth> futuresMonthFromSymbol(String symbol, String commodityCode) {
        if (symbol == null || symbol.isEmpty()) {
            return Optional.empty();
        }
        if (!symbol.startsWith(commodityCode)) {
            throw new MappingError(symbol + " is not a " + commodityCode + " contract");
        }
        String tail = symbol.substring(commodityCode.length());
        if (tail.length() != 3
                || !FuturesMonth.MONTH_CODES.containsKey(tail.substring(0, 1))
                || !isDigits(tail.substring(1))) {
            throw new MappingError("cannot read a delivery month out of " + symbol);
        }
        return Optional.of(
                new FuturesMonth(commodityCode, tail.substring(0, 1), 2000 + Integer.parseInt(tail, 1, 3, 10)));
    }

    public static PurchaseContract contractFromRow(ContractRow row) {
        return PurchaseContract.builder()
                .number(row.getNumber())
                .kind(ContractKind.of(row.getKind()))
                .partyId(row.getPartyCode())
                .commodityCode(row.getCommodityCode())
                .locationCode(row.getLocationCode())
                .writtenOn(row.getWrittenOn())
                .bushels(row.getBushels())
                .window(new DeliveryWindow(row.getWindowOpens(), row.getWindowCloses()))
                .futuresMonth(futuresMonthFromSymbol(row.getFuturesSymbol().orElse(null), row.getCommodityCode())
                        .orElse(null))
                .futuresPrice(row.getFuturesPrice().orElse(null))
                .basis(row.getBasis().orElse(null))
                .flatPrice(row.getFlatPrice().orElse(null))
                .tolerancePercent(row.getTolerancePercent())
                .serviceRatePerBushel(row.getServiceRatePerBushel())
                .status(ContractStatus.of(row.getStatus()))
                .build();
    }

    /** A new row for {@code contract}. */
    public static ContractRow contractToRow(PurchaseContract contract) {
        ContractRow row = new ContractRow();
        writeContract(contract, row);
        return row;
    }

    /**
     * Write every term of {@code contract} onto {@code row}, which keeps its own key and timestamps.
     * An amended contract goes back over its row this way.
     */
    public static void writeContract(PurchaseContract contract, ContractRow row) {
        row.setNumber(contract.number());
        row.setKind(contract.kind().value());
        row.setPartyCode(contract.partyId());
        row.setCommodityCode(contract.commodityCode());
        row.setLocationCode(contract.locationCode());
        row.setWrittenOn(contract.writtenOn());
        row.setBushels(contract.bushels());
        row.setWindowOpens(contract.window().opens());
        row.setWindowCloses(contract.window().closes());
        row.setFuturesSymbol(contract.futuresMonth().map(FuturesMonth::symbol).orElse(null));
        row.setFuturesPrice(contract.futuresPrice().orElse(null));
        row.setBasis(contract.basis().orElse(null));
        row.setFlatPrice(contract.flatPrice().orElse(null));
        row.setTolerancePercent(contract.tolerancePercent());
        row.setServiceRatePerBushel(contract.serviceRatePerBushel());
        row.setStatus(contract.status().value());
    }

    public static SaleContract saleFromRow(SaleRow row) {
        return SaleContract.builder()
                .number(row.getNumber())
                .kind(SaleKind.of(row.getKind()))
                .buyer(row.getBuyer())
                .destination(Destination.of(row.getDestination()))
                .carrier(Carrier.of(row.getCarrier()))
                .commodityCode(row.getCommodityCode())
                .originCode(row.getOriginCode())
                .writtenOn(row.getWrittenOn())
                .bushels(row.getBushels())
                .window(new DeliveryWindow(row.getWindowOpens(), row.getWindowCloses()))
                .specification(new Specification(
                        row.getSpecMaxMoisture(),
                        row.getSpecMinTestWeight(),
                        row.getSpecMaxDamage(),
                        row.getSpecMaxForeign()))
                .futuresMonth(futuresMonthFromSymbol(row.getFuturesSymbol().orElse(null), row.getCommodityCode())
                        .orElse(null))
                .futuresPrice(row.getFuturesPrice().orElse(null))
                .basis(row.getBasis().orElse(null))
                .flatPrice(row.getFlatPrice().orElse(null))
                .tolerancePercent(row.getTolerancePercent())
                .freightPerBushel(row.getFreightPerBushel())
                .shipped(row.getShipped())
                .build();
    }

    /** A new row for {@code sale}, open. */
    public static SaleRow saleToRow(SaleContract sale) {
        SaleRow row = new SaleRow();
        row.setNumber(sale.number());
        row.setKind(sale.kind().value());
        row.setBuyer(sale.buyer());
        row.setDestination(sale.destination().value());
        row.setCarrier(sale.carrier().value());
        row.setCommodityCode(sale.commodityCode());
        row.setOriginCode(sale.originCode());
        row.setWrittenOn(sale.writtenOn());
        row.setBushels(sale.bushels());
        row.setShipped(sale.shipped());
        row.setWindowOpens(sale.window().opens());
        row.setWindowCloses(sale.window().closes());
        row.setFuturesSymbol(sale.futuresMonth().map(FuturesMonth::symbol).orElse(null));
        row.setFuturesPrice(sale.futuresPrice().orElse(null));
        row.setBasis(sale.basis().orElse(null));
        row.setFlatPrice(sale.flatPrice().orElse(null));
        row.setTolerancePercent(sale.tolerancePercent());
        row.setFreightPerBushel(sale.freightPerBushel());
        row.setSpecMaxMoisture(sale.specification().maxMoisture());
        row.setSpecMinTestWeight(sale.specification().minTestWeight());
        row.setSpecMaxDamage(sale.specification().maxDamage());
        row.setSpecMaxForeign(sale.specification().maxForeignMaterial());
        return row;
    }

    /** The statement a row stores, its lines in sequence. */
    public static Settlement settlementFromRow(SettlementRow row) {
        List<SettlementLine> lines = new ArrayList<>();
        for (SettlementLineRow line : row.getLines()) {
            lines.add(new SettlementLine(
                    LineKind.of(line.getKind()),
                    line.getCode(),
                    line.getDescription(),
                    line.getDollars(),
                    line.getQuantity(),
                    line.getRate()));
        }
        return new Settlement(row.getNumber(), row.getSettledOn(), row.getPartyCode(), row.getLocationCode(), lines);
    }

    /** A new row for {@code settlement}, its totals worked out and its lines numbered from one. */
    public static SettlementRow settlementToRow(Settlement settlement) {
        SettlementRow row = new SettlementRow();
        row.setNumber(settlement.number());
        row.setSettledOn(settlement.settledOn());
        row.setPartyCode(settlement.partyId());
        row.setLocationCode(settlement.locationCode());
        row.setGross(settlement.gross());
        row.setDeductions(settlement.deductions());
        row.setNet(settlement.net());
        List<SettlementLineRow> lines = new ArrayList<>();
        int sequence = 1;
        for (SettlementLine line : settlement.lines()) {
            SettlementLineRow stored = new SettlementLineRow();
            stored.setSequence(sequence++);
            stored.setKind(line.kind().value());
            stored.setCode(line.code());
            stored.setDescription(line.description());
            stored.setDollars(line.dollars());
            stored.setQuantity(line.quantity().orElse(null));
            stored.setRate(line.rate().orElse(null));
            lines.add(stored);
        }
        row.setLines(lines);
        return row;
    }

    private static String noneIfEmpty(String text) {
        return text.isEmpty() ? null : text;
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(letter -> letter >= '0' && letter <= '9');
    }
}
