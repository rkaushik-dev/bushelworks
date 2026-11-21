package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Paging;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import io.github.rkaushikdev.bushelworks.api.schemas.SettlementLineOut;
import io.github.rkaushikdev.bushelworks.api.schemas.SettlementOut;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.statement.Statements;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.Deduction;
import io.github.rkaushikdev.bushelworks.services.charges.DiscountBasis;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import io.github.rkaushikdev.bushelworks.services.settlement.RunOptions;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement routes. Settling writes cheques, so nothing below an office key gets in here at all,
 * the reads included, because a settlement shows what a producer was paid.
 *
 * <p>Settling is a POST that creates a document, so it is a POST to the collection and it returns 201
 * with the finished statement. Paying is a POST to a sub resource, because the cheque number and the
 * day it cleared are facts about the payment and not about the settlement.
 *
 * <p>The printable statement is the same resource in another representation: the eighty column form,
 * as text.
 */
@RestController
@RequestMapping("/settlements")
@Requires(Role.OFFICE)
@Tag(name = "settlements")
public class SettlementRoutes {

    /** How the register heads its day: {@code 20 October 2024}. */
    static final DateTimeFormatter REGISTER_DAY = DateTimeFormatter.ofPattern("dd MMMM uuuu", Locale.ENGLISH);

    private final Work work;
    private final RuleBook rules;
    private final Settings settings;

    public SettlementRoutes(Work work, RuleBook rules, Settings settings) {
        this.work = work;
        this.rules = rules;
        this.settings = settings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Settle")
    public SettlementOut settle(@RequestBody SettleIn body, HttpServletResponse response) {
        return work.apply(uow -> {
            RunOptions options = RunOptions.defaults()
                    .withDiscountBasis(body.discountBasis())
                    .withAdvances(body.advances().stream()
                            .map(advance -> new Advance(advance.reference(), advance.dollars(), advance.advancedOn()))
                            .toList())
                    .withStorage(body.storage().stream()
                            .map(storage -> new StorageCharge(storage.lotId(), storage.dollars(), storage.through()))
                            .toList())
                    .withDeductions(body.deductions().stream()
                            .map(deduction ->
                                    new Deduction(deduction.code(), deduction.description(), deduction.dollars()))
                            .toList())
                    .withTicketNumbers(body.ticketNumbers().orElse(null));
            Settlement statement = SettlementRun.settleProducer(
                    uow, body.partyCode(), body.locationCode(), rules, body.settledOn(), body.spotPrice(), options);
            uow.commit();
            response.setHeader(HttpHeaders.LOCATION, "/settlements/" + statement.number());
            return settlementOut(statement);
        });
    }

    @GetMapping("/{number}")
    @Operation(summary = "Read Settlement")
    public SettlementOut readSettlement(@PathVariable("number") String number) {
        return work.apply(uow -> settlementOut(uow.settlements().get(number)));
    }

    @GetMapping("/{number}/statement")
    @Operation(summary = "The printable eighty column statement")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printStatement(@PathVariable("number") String number) {
        String page = work.apply(uow -> Statements.render(uow.settlements().get(number), settings.houseName()));
        return ResponseEntity.ok().contentType(PositionRoutes.TEXT).body(page);
    }

    @PostMapping("/{number}/payment")
    @Operation(summary = "Pay Settlement")
    public SettlementOut paySettlement(@PathVariable("number") String number, @RequestBody PayIn body) {
        return work.apply(uow -> {
            uow.settlements().markPaid(number, body.chequeNumber(), body.paidOn());
            uow.commit();
            return settlementOut(uow.settlements().get(number));
        });
    }

    /** The statements written on a day, or, unless a day is asked for, the ones still unpaid. */
    @GetMapping
    @Operation(summary = "List Settlements")
    public Page<SettlementOut> listSettlements(
            @RequestParam(name = "on", required = false) LocalDate on,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "unpaid_only", defaultValue = "false") boolean unpaidOnly,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow -> {
            List<SettlementRow> rows = unpaidOnly || on == null
                    ? uow.settlements().unpaid()
                    : uow.settlements().forDay(on, locationCode);
            return page.page(rows, row -> settlementOut(Mappers.settlementFromRow(row)));
        });
    }

    @GetMapping("/register/{on}")
    @Operation(summary = "The daily settlement register")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printRegister(
            @PathVariable("on") LocalDate on,
            @RequestParam(name = "location_code", required = false) String locationCode) {
        String text = work.apply(uow -> Statements.register(
                uow.settlements().forDay(on, locationCode).stream()
                        .map(Mappers::settlementFromRow)
                        .toList(),
                on.format(REGISTER_DAY)));
        return ResponseEntity.ok().contentType(PositionRoutes.TEXT).body(text);
    }

    static SettlementOut settlementOut(Settlement settlement) {
        return new SettlementOut(
                settlement.number(),
                settlement.settledOn(),
                settlement.partyId(),
                settlement.locationCode(),
                settlement.gross(),
                settlement.deductions(),
                settlement.net(),
                settlement.ticketNumbers(),
                settlement.contractNumbers(),
                settlement.lines().stream()
                        .map(line -> new SettlementLineOut(
                                line.kind().value(),
                                line.code(),
                                line.description(),
                                line.dollars(),
                                line.quantity(),
                                line.rate()))
                        .toList());
    }

    /**
     * An advance recovered on the statement.
     *
     * @param reference the advance's reference, one to 24 characters
     * @param dollars how much was advanced
     * @param advancedOn the day it was advanced
     */
    public record AdvanceIn(String reference, BigDecimal dollars, LocalDate advancedOn) {

        public AdvanceIn {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(dollars, "dollars");
            Objects.requireNonNull(advancedOn, "advancedOn");
        }

        static AdvanceIn read(Body in) {
            String reference = in.text("reference", 1, 24);
            BigDecimal dollars = in.quantity("dollars");
            LocalDate advancedOn = in.date("advanced_on");
            return in.build(() -> new AdvanceIn(reference, dollars, advancedOn));
        }
    }

    /**
     * A storage bill charged on the statement.
     *
     * @param lotId the lot it bills, one to 24 characters
     * @param dollars how much
     * @param through the day it bills to
     */
    public record StorageIn(String lotId, BigDecimal dollars, LocalDate through) {

        public StorageIn {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(dollars, "dollars");
            Objects.requireNonNull(through, "through");
        }

        static StorageIn read(Body in) {
            String lotId = in.text("lot_id", 1, 24);
            BigDecimal dollars = in.quantity("dollars");
            LocalDate through = in.date("through");
            return in.build(() -> new StorageIn(lotId, dollars, through));
        }
    }

    /**
     * Anything else taken off the statement.
     *
     * @param code the line's code, one to 24 characters
     * @param description what the statement prints, one to 120 characters
     * @param dollars how much
     */
    public record DeductionIn(String code, String description, BigDecimal dollars) {

        public DeductionIn {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(dollars, "dollars");
        }

        static DeductionIn read(Body in) {
            String code = in.text("code", 1, 24);
            String description = in.text("description", 1, 120);
            BigDecimal dollars = in.quantity("dollars");
            return in.build(() -> new DeductionIn(code, description, dollars));
        }
    }

    /**
     * A producer's waiting tickets at a site, settled onto one statement.
     *
     * @param partyCode the producer, one to 24 characters
     * @param locationCode the site, one to 12 characters
     * @param settledOn the day it is settled
     * @param spotPrice the price for bushels no contract prices
     * @param discountBasis which bushel count quality discounts are extended over, the dry bushels
     *     unless the caller says otherwise
     * @param ticketNumbers only these tickets, when given; every waiting ticket otherwise
     * @param advances advances recovered on it
     * @param storage storage bills charged on it
     * @param deductions anything else taken off
     */
    public record SettleIn(
            String partyCode,
            String locationCode,
            LocalDate settledOn,
            BigDecimal spotPrice,
            DiscountBasis discountBasis,
            Optional<List<String>> ticketNumbers,
            List<AdvanceIn> advances,
            List<StorageIn> storage,
            List<DeductionIn> deductions) {

        public SettleIn {
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(locationCode, "locationCode");
            Objects.requireNonNull(settledOn, "settledOn");
            Objects.requireNonNull(spotPrice, "spotPrice");
            Objects.requireNonNull(discountBasis, "discountBasis");
            ticketNumbers = ticketNumbers.map(List::copyOf);
            advances = List.copyOf(advances);
            storage = List.copyOf(storage);
            deductions = List.copyOf(deductions);
        }

        /** A settlement as a caller asks for it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SettleIn from(JsonNode json) {
            return Body.read(json, SettleIn::read);
        }

        static SettleIn read(Body in) {
            String partyCode = in.text("party_code", 1, 24);
            String locationCode = in.text("location_code", 1, 12);
            LocalDate settledOn = in.date("settled_on");
            BigDecimal spotPrice = in.quantity("spot_price");
            DiscountBasis discountBasis = in.choice("discount_basis", DiscountBasis.class, DiscountBasis.DRY);
            Optional<List<String>> ticketNumbers = in.optionalTexts("ticket_numbers");
            List<AdvanceIn> advances = in.list("advances", AdvanceIn::read);
            List<StorageIn> storage = in.list("storage", StorageIn::read);
            List<DeductionIn> deductions = in.list("deductions", DeductionIn::read);
            return in.build(() -> new SettleIn(
                    partyCode,
                    locationCode,
                    settledOn,
                    spotPrice,
                    discountBasis,
                    ticketNumbers,
                    advances,
                    storage,
                    deductions));
        }
    }

    /**
     * The cheque a statement was paid by.
     *
     * @param chequeNumber the cheque, one to 16 characters
     * @param paidOn the day it cleared
     */
    public record PayIn(String chequeNumber, LocalDate paidOn) {

        public PayIn {
            Objects.requireNonNull(chequeNumber, "chequeNumber");
            Objects.requireNonNull(paidOn, "paidOn");
        }

        /** A payment as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PayIn from(JsonNode json) {
            return Body.read(json, PayIn::read);
        }

        static PayIn read(Body in) {
            String chequeNumber = in.text("cheque_number", 1, 16);
            LocalDate paidOn = in.date("paid_on");
            return in.build(() -> new PayIn(chequeNumber, paidOn));
        }
    }
}
