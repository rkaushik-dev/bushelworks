package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Paging;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.services.deferred.Deferred;
import io.github.rkaushikdev.bushelworks.services.deferred.DeferredPosition;
import io.github.rkaushikdev.bushelworks.services.deferred.PricedLot;
import io.github.rkaushikdev.bushelworks.services.deferred.ServiceTerms;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Deferred price routes, for the office.
 *
 * <p>Delivering unpriced grain and pricing it later are two different days and two different
 * documents, so they are two routes. The exposure route is the one the hedge desk polls, because
 * unpriced bushels are the ones the house is long without having paid for. Everything is charged on
 * the standard terms.
 */
@RestController
@RequestMapping("/deferred")
@Requires(Role.OFFICE)
@Tag(name = "deferred")
public class DeferredRoutes {

    private final Work work;
    private final Settings settings;

    public DeferredRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Take Delivery")
    public DeferredLotOut takeDelivery(@RequestBody DeliverIn body) {
        return work.apply(uow -> {
            StorageLotRow row = Deferred.deliver(
                    uow,
                    body.lotId(),
                    body.partyCode(),
                    body.commodityCode(),
                    body.locationCode(),
                    body.bushels(),
                    body.deliveredOn());
            DeferredLotOut out = lotOut(row);
            uow.commit();
            return out;
        });
    }

    @GetMapping("/lots")
    @Operation(summary = "List Lots")
    public Page<DeferredLotOut> listLots(
            @RequestParam(name = "party_code", required = false) String partyCode,
            @RequestParam(name = "commodity_code", required = false) String commodityCode,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow ->
                page.page(Deferred.unpricedLots(uow, partyCode, commodityCode, locationCode), DeferredRoutes::lotOut));
    }

    @PostMapping("/lots/{lot_id}/pricing")
    @Operation(summary = "Price", description = "Fix a price on some or all of a lot, and say what the fee comes to.")
    public PricedOut price(@PathVariable("lot_id") String lotId, @RequestBody PricingIn body) {
        return work.apply(uow -> {
            PricedLot priced = Deferred.priceLot(uow, lotId, body.bushels(), body.unitPrice(), body.pricedOn());
            PricedOut out = new PricedOut(
                    lotId,
                    body.bushels(),
                    body.unitPrice(),
                    priced.gross(),
                    priced.fee(),
                    priced.gross().subtract(priced.fee()));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/position/{party_code}/{commodity_code}")
    @Operation(summary = "Read Position")
    public DeferredPositionOut readPosition(
            @PathVariable("party_code") String partyCode,
            @PathVariable("commodity_code") String commodityCode,
            @RequestParam("as_of") LocalDate asOf) {
        return work.apply(uow -> {
            DeferredPosition found = Deferred.position(uow, partyCode, commodityCode, asOf, ServiceTerms.DEFAULT)
                    .orElseThrow(
                            () -> new LookupFailure(partyCode + " has no unpriced " + commodityCode + " on " + asOf));
            return new DeferredPositionOut(
                    found.partyCode(),
                    found.commodityCode(),
                    found.bushels(),
                    found.oldestDelivery(),
                    found.serviceOwed(),
                    found.overdueLots(),
                    found.isOverdue());
        });
    }

    @GetMapping("/exposure/{commodity_code}")
    @Operation(summary = "Read Exposure", description = "Bushels the house owns because nobody has priced them yet.")
    public Map<String, String> readExposure(
            @PathVariable("commodity_code") String commodityCode, @RequestParam("as_of") LocalDate asOf) {
        return work.apply(uow -> {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("commodity_code", commodityCode);
            out.put("as_of", asOf.toString());
            out.put("bushels", Deferred.houseExposure(uow, commodityCode, asOf).toString());
            out.put("total_unpriced", Deferred.totalUnpriced(uow).toString());
            return out;
        });
    }

    @GetMapping("/overdue")
    @Operation(
            summary = "List Overdue",
            description = "Lots past the term the state allows, which the office has to chase.")
    public List<DeferredLotOut> listOverdue(@RequestParam("as_of") LocalDate asOf) {
        return work.apply(uow -> Deferred.overdue(uow, asOf, ServiceTerms.DEFAULT).stream()
                .map(DeferredRoutes::lotOut)
                .toList());
    }

    private static DeferredLotOut lotOut(StorageLotRow row) {
        return new DeferredLotOut(
                row.getLotId(),
                row.getPartyCode(),
                row.getCommodityCode(),
                row.getLocationCode(),
                row.getBushels(),
                row.getOpenBushels(),
                row.getStoredOn());
    }

    /**
     * Unpriced grain coming in.
     *
     * @param lotId the lot, one to 24 characters
     * @param partyCode whose grain it was, one to 24 characters
     * @param commodityCode what it is, one to eight characters
     * @param locationCode the site, one to 12 characters
     * @param bushels how much
     * @param deliveredOn the day it came in
     */
    public record DeliverIn(
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate deliveredOn) {

        public DeliverIn {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(locationCode, "locationCode");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(deliveredOn, "deliveredOn");
        }

        /** A delivery as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static DeliverIn from(JsonNode json) {
            return Body.read(json, DeliverIn::read);
        }

        static DeliverIn read(Body in) {
            String lotId = in.text("lot_id", 1, 24);
            String partyCode = in.text("party_code", 1, 24);
            String commodityCode = in.text("commodity_code", 1, 8);
            String locationCode = in.text("location_code", 1, 12);
            BigDecimal bushels = in.quantity("bushels");
            LocalDate deliveredOn = in.date("delivered_on");
            return in.build(() -> new DeliverIn(lotId, partyCode, commodityCode, locationCode, bushels, deliveredOn));
        }
    }

    /**
     * A price fixed on some of a lot.
     *
     * @param bushels how much of it
     * @param unitPrice the price per bushel
     * @param pricedOn the day it was fixed
     */
    public record PricingIn(BigDecimal bushels, BigDecimal unitPrice, LocalDate pricedOn) {

        public PricingIn {
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(unitPrice, "unitPrice");
            Objects.requireNonNull(pricedOn, "pricedOn");
        }

        /** A pricing as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PricingIn from(JsonNode json) {
            return Body.read(json, PricingIn::read);
        }

        static PricingIn read(Body in) {
            BigDecimal bushels = in.quantity("bushels");
            BigDecimal unitPrice = in.quantity("unit_price");
            LocalDate pricedOn = in.date("priced_on");
            return in.build(() -> new PricingIn(bushels, unitPrice, pricedOn));
        }
    }

    /**
     * An open deferred lot.
     *
     * @param lotId the lot
     * @param partyCode whose grain it was
     * @param commodityCode what it is
     * @param locationCode the site
     * @param bushels what came in
     * @param openBushels what is still unpriced
     * @param deliveredOn the day it came in
     */
    public record DeferredLotOut(
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            BigDecimal openBushels,
            LocalDate deliveredOn) {

        public DeferredLotOut {
            Objects.requireNonNull(lotId, "lotId");
        }
    }

    /**
     * What a pricing came to.
     *
     * @param lotId the lot
     * @param bushels how much of it was priced
     * @param unitPrice at what price
     * @param gross what the bushels are worth at it
     * @param serviceFee the fee for holding them unpriced
     * @param net the gross less the fee
     */
    public record PricedOut(
            String lotId,
            BigDecimal bushels,
            BigDecimal unitPrice,
            BigDecimal gross,
            BigDecimal serviceFee,
            BigDecimal net) {

        public PricedOut {
            Objects.requireNonNull(lotId, "lotId");
        }
    }

    /**
     * What one producer has unpriced in one commodity.
     *
     * @param partyCode the producer
     * @param commodityCode the commodity
     * @param bushels what is still unpriced
     * @param oldestDelivery the day the oldest open lot came in
     * @param serviceOwed the fee on all of it to the day asked about
     * @param overdueLots the lots past the term, oldest first
     * @param isOverdue whether any of it is past the term
     */
    public record DeferredPositionOut(
            String partyCode,
            String commodityCode,
            BigDecimal bushels,
            LocalDate oldestDelivery,
            BigDecimal serviceOwed,
            List<String> overdueLots,
            boolean isOverdue) {

        public DeferredPositionOut {
            Objects.requireNonNull(partyCode, "partyCode");
            overdueLots = List.copyOf(overdueLots);
        }
    }
}
