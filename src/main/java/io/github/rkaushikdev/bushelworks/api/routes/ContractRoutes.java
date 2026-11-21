package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Paging;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
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
 * Purchase contract routes. Writing a contract commits the house to a price, which is an office job.
 *
 * <p>Pricing an unpriced half is a POST to a sub resource rather than a PATCH on the contract,
 * because setting a basis is an event with a date and a person behind it, not a field edit. The
 * contract that comes back is the amended one.
 */
@RestController
@RequestMapping("/contracts")
@Requires(Role.OFFICE)
@Tag(name = "contracts")
public class ContractRoutes {

    private final Work work;
    private final Settings settings;

    public ContractRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write Contract")
    public ContractOut writeContract(@RequestBody ContractIn body) {
        return work.apply(uow -> {
            PurchaseContract contract = toDomain(body);
            uow.contracts().add(contract);
            uow.commit();
            return contractOut(contract, BigDecimal.ZERO);
        });
    }

    @GetMapping("/{number}")
    @Operation(summary = "Read Contract")
    public ContractOut readContract(@PathVariable("number") String number) {
        return work.apply(
                uow -> contractOut(uow.contracts().get(number), uow.contracts().appliedBushels(number)));
    }

    @PostMapping("/{number}/basis")
    @Operation(summary = "Set Basis")
    public ContractOut setBasis(@PathVariable("number") String number, @RequestBody PriceIn body) {
        return amend(number, contract -> contract.setBasis(body.value(), body.on()));
    }

    @PostMapping("/{number}/futures")
    @Operation(summary = "Set Futures")
    public ContractOut setFutures(@PathVariable("number") String number, @RequestBody PriceIn body) {
        return amend(number, contract -> contract.setFutures(body.value(), body.on()));
    }

    @PostMapping("/{number}/roll")
    @Operation(summary = "Roll Contract")
    public ContractOut rollContract(@PathVariable("number") String number, @RequestBody RollIn body) {
        return amend(
                number,
                contract -> contract.rollTo(
                        new FuturesMonth(contract.commodityCode(), body.monthCode(), body.year()), body.spread()));
    }

    @PostMapping("/{number}/cancel")
    @Operation(summary = "Cancel Contract")
    public ContractOut cancelContract(@PathVariable("number") String number) {
        return amend(number, PurchaseContract::cancel);
    }

    @GetMapping
    @Operation(
            summary = "List Contracts",
            description = "Open contracts for a producer, defaulting to those open today.")
    public Page<ContractOut> listContracts(
            @RequestParam("party_code") String partyCode,
            @RequestParam("commodity_code") String commodityCode,
            @RequestParam(name = "on", required = false) LocalDate on,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        LocalDate day = on == null ? LocalDate.now() : on;
        return work.apply(uow -> page.page(
                uow.contracts().openFor(partyCode, commodityCode, day),
                contract -> contractOut(contract, uow.contracts().appliedBushels(contract.number()))));
    }

    /** Amend the contract on file, keep the amended one, and answer it. */
    private ContractOut amend(String number, UnaryOperator<PurchaseContract> change) {
        return work.apply(uow -> {
            PurchaseContract amended = change.apply(uow.contracts().get(number));
            uow.contracts().replace(amended);
            uow.commit();
            return contractOut(amended, uow.contracts().appliedBushels(number));
        });
    }

    /** The contract the body describes; a futures month only when both its letter and its year are given. */
    private static PurchaseContract toDomain(ContractIn body) {
        FuturesMonth month = null;
        if (body.futuresMonth().filter(code -> !code.isEmpty()).isPresent()
                && body.futuresYear().filter(year -> year != 0).isPresent()) {
            month = new FuturesMonth(
                    body.commodityCode(),
                    body.futuresMonth().get(),
                    body.futuresYear().get());
        }
        return PurchaseContract.builder()
                .number(body.number())
                .kind(body.kind())
                .partyId(body.partyCode())
                .commodityCode(body.commodityCode())
                .locationCode(body.locationCode())
                .writtenOn(body.writtenOn())
                .bushels(body.bushels())
                .window(new DeliveryWindow(body.windowOpens(), body.windowCloses()))
                .futuresMonth(month)
                .futuresPrice(body.futuresPrice().orElse(null))
                .basis(body.basis().orElse(null))
                .flatPrice(body.flatPrice().orElse(null))
                .tolerancePercent(body.tolerancePercent())
                .serviceRatePerBushel(body.serviceRatePerBushel())
                .build();
    }

    private static ContractOut contractOut(PurchaseContract contract, BigDecimal applied) {
        return new ContractOut(
                contract.number(),
                contract.kind().value(),
                contract.partyId(),
                contract.commodityCode(),
                contract.locationCode(),
                contract.writtenOn(),
                contract.bushels(),
                contract.window().opens(),
                contract.window().closes(),
                contract.futuresMonth().map(FuturesMonth::symbol),
                contract.futuresPrice(),
                contract.basis(),
                contract.flatPrice(),
                contract.isFullyPriced() ? Optional.of(contract.unitPrice()) : Optional.empty(),
                contract.isFullyPriced(),
                contract.minimumBushels(),
                contract.maximumBushels(),
                applied,
                contract.status().value());
    }

    /**
     * A contract as the office writes it.
     *
     * @param number the contract number, one to 24 characters
     * @param kind which halves of the price are fixed and when
     * @param partyCode the producer, one to 24 characters
     * @param commodityCode the commodity, one to eight characters
     * @param locationCode where delivery is taken, one to 12 characters
     * @param writtenOn the day it was written
     * @param bushels the contracted quantity
     * @param windowOpens the first day delivery may be made
     * @param windowCloses the last day delivery may be made
     * @param futuresMonth the futures month's letter, when the price is built on one
     * @param futuresYear the futures month's year
     * @param futuresPrice the futures half, when it is set
     * @param basis the basis half, when it is set
     * @param flatPrice the whole price, for a cash or forward contract
     * @param tolerancePercent how far delivery may run over or short and still fill; two percent unless
     *     the office says otherwise
     * @param serviceRatePerBushel the deferred pricing fee per bushel per month started; none unless the
     *     office says otherwise
     */
    public record ContractIn(
            String number,
            ContractKind kind,
            String partyCode,
            String commodityCode,
            String locationCode,
            LocalDate writtenOn,
            BigDecimal bushels,
            LocalDate windowOpens,
            LocalDate windowCloses,
            Optional<String> futuresMonth,
            Optional<Integer> futuresYear,
            Optional<BigDecimal> futuresPrice,
            Optional<BigDecimal> basis,
            Optional<BigDecimal> flatPrice,
            BigDecimal tolerancePercent,
            BigDecimal serviceRatePerBushel) {

        /** The tolerance a contract carries when the office does not say. */
        public static final BigDecimal TOLERANCE_PERCENT = new BigDecimal("2");

        public ContractIn {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(locationCode, "locationCode");
            Objects.requireNonNull(writtenOn, "writtenOn");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(windowOpens, "windowOpens");
            Objects.requireNonNull(windowCloses, "windowCloses");
            Objects.requireNonNull(futuresMonth, "futuresMonth");
            Objects.requireNonNull(futuresYear, "futuresYear");
            Objects.requireNonNull(futuresPrice, "futuresPrice");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(flatPrice, "flatPrice");
            Objects.requireNonNull(tolerancePercent, "tolerancePercent");
            Objects.requireNonNull(serviceRatePerBushel, "serviceRatePerBushel");
        }

        /** A contract as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ContractIn from(JsonNode json) {
            return Body.read(json, ContractIn::read);
        }

        static ContractIn read(Body in) {
            String number = in.text("number", 1, 24);
            ContractKind kind = in.choice("kind", ContractKind.class);
            String partyCode = in.text("party_code", 1, 24);
            String commodityCode = in.text("commodity_code", 1, 8);
            String locationCode = in.text("location_code", 1, 12);
            LocalDate writtenOn = in.date("written_on");
            BigDecimal bushels = in.quantity("bushels");
            LocalDate windowOpens = in.date("window_opens");
            LocalDate windowCloses = in.date("window_closes");
            Optional<String> futuresMonth = in.optionalText("futures_month");
            Optional<Integer> futuresYear = in.optionalWhole("futures_year");
            Optional<BigDecimal> futuresPrice = in.nullableQuantity("futures_price");
            Optional<BigDecimal> basis = in.nullableQuantity("basis");
            Optional<BigDecimal> flatPrice = in.nullableQuantity("flat_price");
            BigDecimal tolerancePercent = in.quantity("tolerance_percent", TOLERANCE_PERCENT);
            BigDecimal serviceRatePerBushel = in.quantity("service_rate_per_bushel", BigDecimal.ZERO);
            return in.build(() -> new ContractIn(
                    number,
                    kind,
                    partyCode,
                    commodityCode,
                    locationCode,
                    writtenOn,
                    bushels,
                    windowOpens,
                    windowCloses,
                    futuresMonth,
                    futuresYear,
                    futuresPrice,
                    basis,
                    flatPrice,
                    tolerancePercent,
                    serviceRatePerBushel));
        }
    }

    /**
     * One half of a price being fixed.
     *
     * @param value the basis or the futures price
     * @param on the day it was fixed
     */
    public record PriceIn(BigDecimal value, LocalDate on) {

        public PriceIn {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(on, "on");
        }

        /** A price as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PriceIn from(JsonNode json) {
            return Body.read(json, PriceIn::read);
        }

        static PriceIn read(Body in) {
            BigDecimal value = in.quantity("value");
            LocalDate on = in.date("on");
            return in.build(() -> new PriceIn(value, on));
        }
    }

    /**
     * A contract's futures moved to a later month.
     *
     * @param monthCode the new month's letter, one character
     * @param year the new month's year
     * @param spread what the move adds to the futures price
     */
    public record RollIn(String monthCode, int year, BigDecimal spread) {

        public RollIn {
            Objects.requireNonNull(monthCode, "monthCode");
            Objects.requireNonNull(spread, "spread");
        }

        /** A roll as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static RollIn from(JsonNode json) {
            return Body.read(json, RollIn::read);
        }

        static RollIn read(Body in) {
            String monthCode = in.text("month_code", 1, 1);
            Integer year = in.whole("year", Integer.MIN_VALUE, Integer.MAX_VALUE);
            BigDecimal spread = in.quantity("spread");
            return in.build(() -> new RollIn(monthCode, year, spread));
        }
    }

    /**
     * A contract, as it stands.
     *
     * @param number the contract number
     * @param kind which halves of the price are fixed and when
     * @param partyCode the producer
     * @param commodityCode the commodity
     * @param locationCode where delivery is taken
     * @param writtenOn the day it was written
     * @param bushels the contracted quantity
     * @param windowOpens the first day delivery may be made
     * @param windowCloses the last day delivery may be made
     * @param futuresSymbol the futures month, such as {@code CORNZ24}, when the price is built on one
     * @param futuresPrice the futures half, once set
     * @param basis the basis half, once set
     * @param flatPrice the whole price, for a cash or forward contract
     * @param unitPrice the price per bushel, once both halves are fixed
     * @param isFullyPriced whether both halves are fixed
     * @param minimumBushels the fewest bushels that fill it
     * @param maximumBushels the most bushels it takes
     * @param appliedBushels the bushels delivered against it so far
     * @param status where it stands
     */
    public record ContractOut(
            String number,
            String kind,
            String partyCode,
            String commodityCode,
            String locationCode,
            LocalDate writtenOn,
            BigDecimal bushels,
            LocalDate windowOpens,
            LocalDate windowCloses,
            Optional<String> futuresSymbol,
            Optional<BigDecimal> futuresPrice,
            Optional<BigDecimal> basis,
            Optional<BigDecimal> flatPrice,
            Optional<BigDecimal> unitPrice,
            boolean isFullyPriced,
            BigDecimal minimumBushels,
            BigDecimal maximumBushels,
            BigDecimal appliedBushels,
            String status) {

        public ContractOut {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(futuresSymbol, "futuresSymbol");
            Objects.requireNonNull(futuresPrice, "futuresPrice");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(flatPrice, "flatPrice");
            Objects.requireNonNull(unitPrice, "unitPrice");
        }
    }
}
