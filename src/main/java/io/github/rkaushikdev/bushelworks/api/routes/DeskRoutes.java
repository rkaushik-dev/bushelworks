package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.hedging.Account;
import io.github.rkaushikdev.bushelworks.domain.hedging.Lot;
import io.github.rkaushikdev.bushelworks.domain.hedging.Side;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.desk.Desk;
import io.github.rkaushikdev.bushelworks.services.desk.Gap;
import io.github.rkaushikdev.bushelworks.services.desk.Sheet;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hedge desk routes.
 *
 * <p>The broker statement is not on file, so the sheet is a POST even though it changes nothing. The
 * fills go up in the body, the position comes off the database, and the answer comes back. Putting
 * the fills in a query string was tried and the URL ran past what the office proxy would log.
 *
 * <p>Everything here needs an office key. The gap between the book and the hedge is the one number
 * that says what the house is actually exposed to, and it is not something the scale terminal or a
 * producer portal has any business reading.
 */
@RestController
@RequestMapping("/desk")
@Requires(Role.OFFICE)
@Tag(name = "desk")
public class DeskRoutes {

    private final Work work;

    public DeskRoutes(Work work) {
        this.work = work;
    }

    @PostMapping("/sheet")
    @Operation(
            summary = "Build Sheet",
            description = "The morning sheet: position against broker, one line per commodity.")
    public SheetOut buildSheet(@RequestBody SheetIn body) {
        return work.apply(uow -> {
            Sheet built = sheet(uow, body);
            return new SheetOut(
                    built.asOf(),
                    built.locationCode(),
                    built.totalGapContracts(),
                    built.gaps().stream().map(DeskRoutes::gapOut).toList());
        });
    }

    @PostMapping("/sheet/actionable")
    @Operation(
            summary = "Build Actionable",
            description = "Only the lines worth a telephone call, for the screen in the office.")
    public List<GapOut> buildActionable(@RequestBody SheetIn body) {
        return work.apply(uow ->
                sheet(uow, body).actionable().stream().map(DeskRoutes::gapOut).toList());
    }

    /**
     * Open gain or loss per month against the settlement prices handed in.
     *
     * <p>Nothing is read off the database here. It is the futures side on its own, which is what gets
     * reconciled against the broker statement.
     */
    @PostMapping("/exposure")
    @Operation(
            summary = "Read Exposure",
            description = "Open gain or loss per month against the settlements handed in.")
    public Map<String, String> readExposure(@RequestBody ExposureIn body) {
        Map<String, Account> accounts = accounts(body.fills());
        Map<FuturesMonth, BigDecimal> prices = new HashMap<>();
        for (FillIn fill : body.fills()) {
            FuturesMonth month = fill.month();
            BigDecimal settle = body.settlements().get(month.symbol());
            if (settle != null) {
                prices.put(month, settle);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Account account : accounts.values()) {
            Desk.monthExposure(account, prices).forEach((symbol, value) -> out.put(symbol, value.toString()));
        }
        return out;
    }

    @GetMapping("/basis")
    @Operation(summary = "Read Basis", description = "Cash less futures, for the board out front.")
    public Map<String, String> readBasis(
            @RequestParam("cash_bid") BigDecimal cashBid, @RequestParam("futures") BigDecimal futures) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("cash_bid", cashBid.toString());
        out.put("futures", futures.toString());
        out.put("basis", Desk.basis(cashBid, futures).toString());
        return out;
    }

    @GetMapping("/carry")
    @Operation(summary = "Read Carry", description = "Whether the spread pays to hold the grain rather than sell it.")
    public Map<String, String> readCarry(
            @RequestParam("near") BigDecimal near,
            @RequestParam("deferred") BigDecimal deferred,
            @RequestParam("months") int months,
            @RequestParam("storage_per_month") BigDecimal storagePerMonth) {
        BigDecimal perBushel = Desk.carry(near, deferred, months, storagePerMonth);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("spread", deferred.subtract(near).toString());
        out.put("months", Integer.toString(months));
        out.put("carry_per_bushel", perBushel.toString());
        out.put("pays_to_store", perBushel.signum() > 0 ? "yes" : "no");
        return out;
    }

    private static Sheet sheet(UnitOfWork uow, SheetIn body) {
        return Desk.sheet(
                uow, body.asOf(), accounts(body.fills()), body.commodities().orElse(null), body.locationCode());
    }

    /**
     * One account per commodity. A corn short does not offset a bean long, and putting every fill in
     * one account would let it look as though it did.
     */
    private static Map<String, Account> accounts(List<FillIn> fills) {
        Map<String, Account> accounts = new LinkedHashMap<>();
        for (FillIn fill : fills) {
            accounts.computeIfAbsent(fill.commodityCode(), code -> new Account())
                    .add(fill.lot());
        }
        return accounts;
    }

    private static GapOut gapOut(Gap gap) {
        return new GapOut(
                gap.commodityCode(),
                gap.netLong(),
                gap.hedgedBushels(),
                gap.wantContracts(),
                gap.onContracts(),
                gap.gapContracts(),
                gap.gapBushels(),
                gap.side(),
                gap.isActionable(),
                gap.isOverHedged());
    }

    /**
     * One line off the broker statement.
     *
     * @param reference the broker's reference for the fill, one to 24 characters
     * @param commodityCode the commodity, one to eight characters
     * @param monthCode the delivery month's letter, one character
     * @param year the delivery month's year, 2000 to 2100
     * @param side which way the fill went
     * @param contracts how many contracts, one to 2,000
     * @param price the fill price
     * @param filledOn the day it filled
     */
    public record FillIn(
            String reference,
            String commodityCode,
            String monthCode,
            int year,
            Side side,
            int contracts,
            BigDecimal price,
            LocalDate filledOn) {

        public FillIn {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(monthCode, "monthCode");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(filledOn, "filledOn");
        }

        static FillIn read(Body in) {
            String reference = in.text("reference", 1, 24);
            String commodityCode = in.text("commodity_code", 1, 8);
            String monthCode = in.text("month_code", 1, 1);
            Integer year = in.whole("year", 2000, 2100);
            Side side = in.choice("side", Side.class);
            Integer contracts = in.wholeAbove("contracts", 0, 2000);
            BigDecimal price = in.quantity("price");
            LocalDate filledOn = in.date("filled_on");
            return in.build(
                    () -> new FillIn(reference, commodityCode, monthCode, year, side, contracts, price, filledOn));
        }

        /**
         * The futures month the fill is in.
         *
         * @throws io.github.rkaushikdev.bushelworks.domain.pricing.PricingError when the commodity does
         *     not trade that month
         */
        FuturesMonth month() {
            return new FuturesMonth(commodityCode, monthCode, year);
        }

        /** The fill as the account carries it. */
        Lot lot() {
            return new Lot(reference, month(), side, contracts, price, filledOn);
        }
    }

    /**
     * What the morning sheet is asked for.
     *
     * @param asOf the day the position is read on
     * @param locationCode one site, or {@code *} for the whole house, which it is unless the caller says
     * @param commodities only these commodities, when given; every one on the position otherwise
     * @param fills the broker statement's fills, none unless the caller sends some
     */
    public record SheetIn(LocalDate asOf, String locationCode, Optional<List<String>> commodities, List<FillIn> fills) {

        public SheetIn {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(locationCode, "locationCode");
            commodities = commodities.map(List::copyOf);
            fills = List.copyOf(fills);
        }

        /** A sheet as a caller asks for it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SheetIn from(JsonNode json) {
            return Body.read(json, SheetIn::read);
        }

        static SheetIn read(Body in) {
            LocalDate asOf = in.date("as_of");
            String locationCode = in.text("location_code", Positions.EVERY_SITE);
            Optional<List<String>> commodities = in.optionalTexts("commodities");
            List<FillIn> fills = in.list("fills", FillIn::read);
            return in.build(() -> new SheetIn(asOf, locationCode, commodities, fills));
        }
    }

    /**
     * Fills to value against the day's settlement prices.
     *
     * @param fills the broker statement's fills, none unless the caller sends some
     * @param settlements each month's settlement price, keyed by its symbol such as {@code CORNZ24}
     */
    public record ExposureIn(List<FillIn> fills, Map<String, BigDecimal> settlements) {

        public ExposureIn {
            fills = List.copyOf(fills);
            settlements = Map.copyOf(settlements);
        }

        /** Fills and prices as a caller sends them. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ExposureIn from(JsonNode json) {
            return Body.read(json, ExposureIn::read);
        }

        static ExposureIn read(Body in) {
            List<FillIn> fills = in.list("fills", FillIn::read);
            Map<String, BigDecimal> settlements = in.quantities("settlements");
            return in.build(() -> new ExposureIn(fills, settlements));
        }
    }

    /**
     * One commodity on the sheet, and how far the hedge is from the position.
     *
     * @param commodityCode the commodity
     * @param netLong the bushels the house carries the price risk on
     * @param hedgedBushels the bushels the broker has the house short
     * @param wantContracts the whole contracts the position calls for
     * @param onContracts the contracts the house is short with the broker
     * @param gapContracts the contracts still to sell; below nought, to buy back
     * @param gapBushels the gap in bushels
     * @param side the side an order closing the gap would be on
     * @param isActionable whether the gap is worth ringing the broker about
     * @param isOverHedged whether the house is short more than it owns
     */
    public record GapOut(
            String commodityCode,
            BigDecimal netLong,
            BigDecimal hedgedBushels,
            int wantContracts,
            int onContracts,
            int gapContracts,
            BigDecimal gapBushels,
            Side side,
            boolean isActionable,
            boolean isOverHedged) {

        public GapOut {
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(side, "side");
        }
    }

    /**
     * The morning sheet.
     *
     * @param asOf the day the position was read on
     * @param locationCode the site, or {@code *} for the whole house
     * @param totalGapContracts the gaps added up across the board, a headline and not a plan
     * @param lines one per commodity
     */
    public record SheetOut(LocalDate asOf, String locationCode, int totalGapContracts, List<GapOut> lines) {

        public SheetOut {
            Objects.requireNonNull(asOf, "asOf");
            lines = List.copyOf(lines);
        }
    }
}
