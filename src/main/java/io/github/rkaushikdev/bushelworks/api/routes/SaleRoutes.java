package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Paging;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.sales.Carrier;
import io.github.rkaushikdev.bushelworks.domain.sales.Destination;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleContract;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleKind;
import io.github.rkaushikdev.bushelworks.domain.sales.Specification;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ShipmentRow;
import io.github.rkaushikdev.bushelworks.services.shipping.Shipping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sale and shipment routes, for the office.
 *
 * <p>Loading out is a POST to the sale's shipments. Posting the buyer's settlement is a POST to one
 * shipment, and rejecting a car is a third, because all three are things that happen on different
 * days and are done by different people.
 */
@RestController
@RequestMapping("/sales")
@Requires(Role.OFFICE)
@Tag(name = "sales")
public class SaleRoutes {

    private static final BigDecimal NO_BUSHELS = new BigDecimal("0.0000");

    private final Work work;
    private final Settings settings;

    public SaleRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    /**
     * Write a sale. It is built as a sale first, so the sale's own rules refuse a bad body before it
     * reaches a check constraint: a constraint violation tells the caller nothing.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write Sale")
    public SaleOut writeSale(@RequestBody SaleIn body) {
        return work.apply(uow -> {
            if (isOnFile(uow, body.number())) {
                throw new ConflictError("sale " + body.number() + " already exists");
            }
            SaleRow row = Mappers.saleToRow(toDomain(body));
            uow.session().persist(row);
            uow.flush();
            SaleOut out = saleOut(row);
            uow.commit();
            return out;
        });
    }

    @GetMapping("/{number}")
    @Operation(summary = "Read Sale")
    public SaleOut readSale(@PathVariable("number") String number) {
        return work.apply(uow -> saleOut(Shipping.sale(uow, number)));
    }

    @GetMapping
    @Operation(summary = "List Sales")
    public Page<SaleOut> listSales(
            @RequestParam(name = "commodity_code", required = false) String commodityCode,
            @RequestParam(name = "on", required = false) LocalDate on,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow -> page.page(Shipping.openSales(uow, commodityCode, on), SaleRoutes::saleOut));
    }

    @PostMapping("/{number}/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ship")
    public SaleOut ship(@PathVariable("number") String number, @RequestBody LoadIn body) {
        return work.apply(uow -> {
            Shipping.loadOut(
                    uow,
                    number,
                    body.reference(),
                    body.pounds(),
                    body.shippedOn(),
                    body.carrierId().orElse(null));
            SaleOut out = saleOut(Shipping.sale(uow, number));
            uow.commit();
            return out;
        });
    }

    @PostMapping("/{number}/shipments/{reference}/settlement")
    @Operation(summary = "Settle")
    public ShipmentOut settle(
            @PathVariable("number") String number,
            @PathVariable("reference") String reference,
            @RequestBody SettleShipmentIn body) {
        return work.apply(uow -> {
            ShipmentRow row = Shipping.settleShipment(
                    uow,
                    number,
                    reference,
                    body.destinationPounds(),
                    body.destinationGrade(),
                    body.proceeds(),
                    body.settledOn());
            ShipmentOut out = shipmentOut(row);
            uow.commit();
            return out;
        });
    }

    @PostMapping("/{number}/shipments/{reference}/rejection")
    @Operation(summary = "Reject")
    public ShipmentOut reject(
            @PathVariable("number") String number,
            @PathVariable("reference") String reference,
            @RequestParam("reason")
                    @Size(min = 1, message = "String should have at least 1 character")
                    @Size(max = 200, message = "String should have at most 200 characters")
                    String reason) {
        return work.apply(uow -> {
            ShipmentRow row = Shipping.rejectShipment(uow, number, reference, reason);
            ShipmentOut out = shipmentOut(row);
            uow.commit();
            return out;
        });
    }

    @GetMapping("/position/{commodity_code}")
    @Operation(
            summary = "Read Sold Forward",
            description = "Bushels sold and not yet loaded, which is what makes the house short.")
    public Map<String, String> readSoldForward(
            @PathVariable("commodity_code") String commodityCode,
            @RequestParam("on") LocalDate on,
            @RequestParam(name = "origin_code", required = false) String originCode) {
        return work.apply(uow -> {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("commodity_code", commodityCode);
            out.put("on", on.toString());
            out.put(
                    "sold_forward",
                    Shipping.soldForward(uow, commodityCode, on, originCode).toString());
            return out;
        });
    }

    private static boolean isOnFile(UnitOfWork uow, String number) {
        return !uow.session()
                .createQuery("select s.id from SaleRow s where s.number = :number", Integer.class)
                .setParameter("number", number)
                .getResultList()
                .isEmpty();
    }

    private static SaleContract toDomain(SaleIn body) {
        return SaleContract.builder()
                .number(body.number())
                .kind(SaleKind.of(body.kind()))
                .buyer(body.buyer())
                .destination(Destination.of(body.destination()))
                .carrier(Carrier.of(body.carrier()))
                .commodityCode(body.commodityCode())
                .originCode(body.originCode())
                .writtenOn(body.writtenOn())
                .bushels(body.bushels())
                .window(new DeliveryWindow(body.windowOpens(), body.windowCloses()))
                .specification(new Specification(
                        body.specMaxMoisture(), body.specMinTestWeight(), body.specMaxDamage(), body.specMaxForeign()))
                .futuresPrice(body.futuresPrice().orElse(null))
                .basis(body.basis().orElse(null))
                .flatPrice(body.flatPrice().orElse(null))
                .tolerancePercent(body.tolerancePercent())
                .freightPerBushel(body.freightPerBushel())
                .build();
    }

    private static ShipmentOut shipmentOut(ShipmentRow row) {
        return new ShipmentOut(
                row.getReference(),
                row.getShippedOn(),
                row.getCarrierId(),
                row.getOriginBushels(),
                row.getDestinationBushels(),
                row.getDestinationGrade(),
                row.getSettledOn(),
                row.getProceeds(),
                row.isRejected(),
                row.getRejectionReason(),
                row.getTransitShrink());
    }

    private static SaleOut saleOut(SaleRow row) {
        BigDecimal left = row.getBushels().subtract(row.getShipped());
        return new SaleOut(
                row.getNumber(),
                row.getKind(),
                row.getBuyer(),
                row.getDestination(),
                row.getCarrier(),
                row.getCommodityCode(),
                row.getOriginCode(),
                row.getWrittenOn(),
                row.getBushels(),
                row.getShipped(),
                left.signum() > 0 ? left : NO_BUSHELS,
                row.getWindowOpens(),
                row.getWindowCloses(),
                row.getFreightPerBushel(),
                row.getStatus(),
                row.getShipments().stream().map(SaleRoutes::shipmentOut).toList());
    }

    /**
     * A sale as the office writes it.
     *
     * @param number the sale number, one to 24 characters
     * @param kind {@code spot}, {@code forward} or {@code basis}, up to 12 characters; forward unless
     *     the office says otherwise
     * @param buyer who bought it, one to 80 characters
     * @param destination what kind of buyer, up to 16 characters; a terminal unless the office says
     *     otherwise
     * @param carrier how it moves, up to eight characters; by truck unless the office says otherwise
     * @param commodityCode the commodity, one to eight characters
     * @param originCode the site it ships from, one to 12 characters
     * @param writtenOn the day it was written
     * @param bushels the face amount
     * @param windowOpens the first day it may ship
     * @param windowCloses the last day it may ship
     * @param flatPrice the whole price, for a flat priced sale
     * @param futuresPrice the futures half, when it is set
     * @param basis the basis half, when it is set
     * @param freightPerBushel what the house pays to get it there; nothing unless given
     * @param tolerancePercent how far over the face amount the last car may run; two percent unless given
     * @param specMaxMoisture the most moisture the buyer takes; 15.0 unless given
     * @param specMinTestWeight the least test weight the buyer takes; 54.0 unless given
     * @param specMaxDamage the most damage the buyer takes; 5.0 unless given
     * @param specMaxForeign the most foreign material the buyer takes; 3.0 unless given
     */
    public record SaleIn(
            String number,
            String kind,
            String buyer,
            String destination,
            String carrier,
            String commodityCode,
            String originCode,
            LocalDate writtenOn,
            BigDecimal bushels,
            LocalDate windowOpens,
            LocalDate windowCloses,
            Optional<BigDecimal> flatPrice,
            Optional<BigDecimal> futuresPrice,
            Optional<BigDecimal> basis,
            BigDecimal freightPerBushel,
            BigDecimal tolerancePercent,
            BigDecimal specMaxMoisture,
            BigDecimal specMinTestWeight,
            BigDecimal specMaxDamage,
            BigDecimal specMaxForeign) {

        public SaleIn {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(buyer, "buyer");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(originCode, "originCode");
            Objects.requireNonNull(writtenOn, "writtenOn");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(windowOpens, "windowOpens");
            Objects.requireNonNull(windowCloses, "windowCloses");
            Objects.requireNonNull(flatPrice, "flatPrice");
            Objects.requireNonNull(futuresPrice, "futuresPrice");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(freightPerBushel, "freightPerBushel");
            Objects.requireNonNull(tolerancePercent, "tolerancePercent");
            Objects.requireNonNull(specMaxMoisture, "specMaxMoisture");
            Objects.requireNonNull(specMinTestWeight, "specMinTestWeight");
            Objects.requireNonNull(specMaxDamage, "specMaxDamage");
            Objects.requireNonNull(specMaxForeign, "specMaxForeign");
        }

        /** A sale as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SaleIn from(JsonNode json) {
            return Body.read(json, SaleIn::read);
        }

        static SaleIn read(Body in) {
            String number = in.text("number", 1, 24);
            String kind = in.text("kind", SaleKind.FORWARD.value(), 12);
            String buyer = in.text("buyer", 1, 80);
            String destination = in.text("destination", Destination.TERMINAL.value(), 16);
            String carrier = in.text("carrier", Carrier.TRUCK.value(), 8);
            String commodityCode = in.text("commodity_code", 1, 8);
            String originCode = in.text("origin_code", 1, 12);
            LocalDate writtenOn = in.date("written_on");
            BigDecimal bushels = in.quantity("bushels");
            LocalDate windowOpens = in.date("window_opens");
            LocalDate windowCloses = in.date("window_closes");
            Optional<BigDecimal> flatPrice = in.nullableQuantity("flat_price");
            Optional<BigDecimal> futuresPrice = in.nullableQuantity("futures_price");
            Optional<BigDecimal> basis = in.nullableQuantity("basis");
            BigDecimal freightPerBushel = in.quantity("freight_per_bushel", BigDecimal.ZERO);
            BigDecimal tolerancePercent = in.quantity("tolerance_percent", new BigDecimal("2"));
            BigDecimal specMaxMoisture = in.quantity("spec_max_moisture", new BigDecimal("15.0"));
            BigDecimal specMinTestWeight = in.quantity("spec_min_test_weight", new BigDecimal("54.0"));
            BigDecimal specMaxDamage = in.quantity("spec_max_damage", new BigDecimal("5.0"));
            BigDecimal specMaxForeign = in.quantity("spec_max_foreign", new BigDecimal("3.0"));
            return in.build(() -> new SaleIn(
                    number,
                    kind,
                    buyer,
                    destination,
                    carrier,
                    commodityCode,
                    originCode,
                    writtenOn,
                    bushels,
                    windowOpens,
                    windowCloses,
                    flatPrice,
                    futuresPrice,
                    basis,
                    freightPerBushel,
                    tolerancePercent,
                    specMaxMoisture,
                    specMinTestWeight,
                    specMaxDamage,
                    specMaxForeign));
        }
    }

    /**
     * A car or truck loaded out against a sale.
     *
     * @param reference the car or truck, one to 24 characters
     * @param pounds what it weighed out at
     * @param shippedOn the day it went
     * @param carrierId the car or truck's own identifier, when there is one
     */
    public record LoadIn(String reference, BigDecimal pounds, LocalDate shippedOn, Optional<String> carrierId) {

        public LoadIn {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(pounds, "pounds");
            Objects.requireNonNull(shippedOn, "shippedOn");
            Objects.requireNonNull(carrierId, "carrierId");
        }

        /** A load as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static LoadIn from(JsonNode json) {
            return Body.read(json, LoadIn::read);
        }

        static LoadIn read(Body in) {
            String reference = in.text("reference", 1, 24);
            BigDecimal pounds = in.quantity("pounds");
            LocalDate shippedOn = in.date("shipped_on");
            Optional<String> carrierId = in.optionalText("carrier_id");
            return in.build(() -> new LoadIn(reference, pounds, shippedOn, carrierId));
        }
    }

    /**
     * The buyer's settlement on one shipment.
     *
     * @param destinationPounds what it weighed at destination
     * @param destinationGrade the grade the buyer gave it, one to eight characters
     * @param proceeds the buyer's cheque
     * @param settledOn the day the buyer settled
     */
    public record SettleShipmentIn(
            BigDecimal destinationPounds, String destinationGrade, BigDecimal proceeds, LocalDate settledOn) {

        public SettleShipmentIn {
            Objects.requireNonNull(destinationPounds, "destinationPounds");
            Objects.requireNonNull(destinationGrade, "destinationGrade");
            Objects.requireNonNull(proceeds, "proceeds");
            Objects.requireNonNull(settledOn, "settledOn");
        }

        /** A buyer's settlement as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SettleShipmentIn from(JsonNode json) {
            return Body.read(json, SettleShipmentIn::read);
        }

        static SettleShipmentIn read(Body in) {
            BigDecimal destinationPounds = in.quantity("destination_pounds");
            String destinationGrade = in.text("destination_grade", 1, 8);
            BigDecimal proceeds = in.quantity("proceeds");
            LocalDate settledOn = in.date("settled_on");
            return in.build(() -> new SettleShipmentIn(destinationPounds, destinationGrade, proceeds, settledOn));
        }
    }

    /**
     * One car or truck shipped against a sale.
     *
     * @param reference the car or truck
     * @param shippedOn the day it went
     * @param carrierId its own identifier, when there is one
     * @param originBushels the bushels it carried, weighed at origin
     * @param destinationBushels the bushels the buyer weighed, once settled
     * @param destinationGrade the grade the buyer gave it, once settled
     * @param settledOn the day the buyer settled, once they have
     * @param proceeds the buyer's cheque, once settled
     * @param rejected whether the buyer refused it
     * @param rejectionReason why, when they did
     * @param transitShrink the bushels lost between the two scales, once settled
     */
    public record ShipmentOut(
            String reference,
            LocalDate shippedOn,
            Optional<String> carrierId,
            BigDecimal originBushels,
            Optional<BigDecimal> destinationBushels,
            Optional<String> destinationGrade,
            Optional<LocalDate> settledOn,
            Optional<BigDecimal> proceeds,
            boolean rejected,
            Optional<String> rejectionReason,
            Optional<BigDecimal> transitShrink) {

        public ShipmentOut {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * A sale with what has shipped against it.
     *
     * @param number the sale number
     * @param kind spot, forward or basis
     * @param buyer who bought it
     * @param destination what kind of buyer
     * @param carrier how it moves
     * @param commodityCode the commodity
     * @param originCode the site it ships from
     * @param writtenOn the day it was written
     * @param bushels the face amount
     * @param shipped what has been loaded against it
     * @param remaining what it still wants, never below zero
     * @param windowOpens the first day it may ship
     * @param windowCloses the last day it may ship
     * @param freightPerBushel what the house pays to get it there
     * @param status {@code open} until it fills
     * @param shipments what has been loaded, in the order it went
     */
    public record SaleOut(
            String number,
            String kind,
            String buyer,
            String destination,
            String carrier,
            String commodityCode,
            String originCode,
            LocalDate writtenOn,
            BigDecimal bushels,
            BigDecimal shipped,
            BigDecimal remaining,
            LocalDate windowOpens,
            LocalDate windowCloses,
            BigDecimal freightPerBushel,
            String status,
            List<ShipmentOut> shipments) {

        public SaleOut {
            Objects.requireNonNull(number, "number");
            shipments = List.copyOf(shipments);
        }
    }
}
