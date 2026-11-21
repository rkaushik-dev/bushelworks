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
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.persistence.models.WarehouseReceiptRow;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.github.rkaushikdev.bushelworks.services.warehousing.AccrualRun;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import io.github.rkaushikdev.bushelworks.services.warehousing.WithdrawalOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * Storage routes. The accrual run bills producers and a receipt is a negotiable instrument, so every
 * route needs an office key.
 *
 * <p>The accrual run is a POST because it changes what every lot has been billed to. It takes a
 * {@code dry_run} flag rather than having a separate preview route: the office wants to see the same
 * numbers it is about to commit, and two code paths would eventually disagree.
 */
@RestController
@RequestMapping("/storage")
@Requires(Role.OFFICE)
@Tag(name = "storage")
public class StorageRoutes {

    private final Work work;
    private final RuleBook rules;
    private final Settings settings;

    public StorageRoutes(Work work, RuleBook rules, Settings settings) {
        this.work = work;
        this.rules = rules;
        this.settings = settings;
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open Lot")
    public LotOut openLot(@RequestBody StoreIn body) {
        return work.apply(uow -> {
            StorageLotRow row = Warehousing.store(
                    uow,
                    body.lotId(),
                    body.partyCode(),
                    body.commodityCode(),
                    body.locationCode(),
                    body.bushels(),
                    body.storedOn(),
                    null,
                    body.deferredPrice());
            LotOut out = lotOut(row);
            uow.commit();
            return out;
        });
    }

    @GetMapping("/lots/{lot_id}")
    @Operation(summary = "Read Lot")
    public LotOut readLot(@PathVariable("lot_id") String lotId) {
        return work.apply(uow -> lotOut(Warehousing.lot(uow, lotId)));
    }

    @GetMapping("/lots")
    @Operation(summary = "List Lots")
    public Page<LotOut> listLots(
            @RequestParam(name = "party_code", required = false) String partyCode,
            @RequestParam(name = "commodity_code", required = false) String commodityCode,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow ->
                page.page(Warehousing.openLots(uow, partyCode, commodityCode, locationCode), StorageRoutes::lotOut));
    }

    /** Draw a producer's bushels out, oldest lot first, and answer every lot the withdrawal touched. */
    @PostMapping("/withdrawals")
    @Operation(summary = "Make Withdrawal")
    public List<LotOut> makeWithdrawal(@RequestBody WithdrawIn body) {
        return work.apply(uow -> {
            WithdrawalOptions options = WithdrawalOptions.defaults()
                    .withReason(body.reason())
                    .withSettlementNumber(body.settlementNumber().orElse(null))
                    .withLocationCode(body.locationCode().orElse(null));
            List<LotOut> touched = Warehousing.withdraw(
                            uow, body.partyCode(), body.commodityCode(), body.bushels(), body.withdrawnOn(), options)
                    .stream()
                    .map(withdrawal -> lotOut(withdrawal.getLot()))
                    .toList();
            uow.commit();
            return touched;
        });
    }

    /** Charge storage on the open lots up to a day; a dry run works the charges out and keeps none of it. */
    @PostMapping("/accruals")
    @Operation(summary = "Run Accrual")
    public AccrualOut runAccrual(
            @RequestParam("through") LocalDate through,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun) {
        return work.apply(uow -> {
            AccrualRun run = Warehousing.accrue(uow, rules, through, locationCode, !dryRun);
            AccrualOut out = new AccrualOut(
                    run.through(),
                    run.total(),
                    !dryRun,
                    run.lines().stream()
                            .map(line -> new AccrualLineOut(
                                    line.lotId(),
                                    line.partyCode(),
                                    line.commodityCode(),
                                    line.openBushels(),
                                    line.fromDay(),
                                    line.through(),
                                    line.dollars()))
                            .toList());
            if (dryRun) {
                uow.rollback();
            } else {
                uow.commit();
            }
            return out;
        });
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Write Receipt")
    public ReceiptOut writeReceipt(@RequestBody ReceiptIn body) {
        return work.apply(uow -> {
            WarehouseReceiptRow row = Warehousing.issueReceipt(
                    uow,
                    body.receiptNumber(),
                    body.lotId(),
                    body.grade(),
                    body.issuedOn(),
                    body.lienholder().orElse(null));
            ReceiptOut out = receiptOut(row);
            uow.commit();
            return out;
        });
    }

    @PostMapping("/receipts/{receipt_number}/cancel")
    @Operation(summary = "Cancel")
    public ReceiptOut cancel(@PathVariable("receipt_number") String receiptNumber, @RequestParam("on") LocalDate on) {
        return work.apply(uow -> {
            ReceiptOut out = receiptOut(Warehousing.cancelReceipt(uow, receiptNumber, on));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/on-hand/{commodity_code}")
    @Operation(
            summary = "Read On Hand",
            description = "Bushels the house is holding for producers, which it does not own.")
    public Map<String, String> readOnHand(
            @PathVariable("commodity_code") String commodityCode,
            @RequestParam(name = "location_code", required = false) String locationCode) {
        return work.apply(uow -> {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("commodity_code", commodityCode);
            out.put(
                    "location_code",
                    locationCode == null || locationCode.isEmpty() ? Positions.EVERY_SITE : locationCode);
            out.put(
                    "bushels",
                    Warehousing.onHand(uow, commodityCode, locationCode).toString());
            return out;
        });
    }

    static LotOut lotOut(StorageLotRow row) {
        return new LotOut(
                row.getLotId(),
                row.getPartyCode(),
                row.getCommodityCode(),
                row.getLocationCode(),
                row.getBushels(),
                row.getOpenBushels(),
                row.getStoredOn(),
                row.getClosedOn(),
                row.getBilledThrough(),
                row.getAccruedToDate(),
                row.isDeferredPrice());
    }

    private static ReceiptOut receiptOut(WarehouseReceiptRow row) {
        return new ReceiptOut(
                row.getReceiptNumber(),
                row.getPartyCode(),
                row.getCommodityCode(),
                row.getLocationCode(),
                row.getBushels(),
                row.getGrade(),
                row.getIssuedOn(),
                row.getCancelledOn(),
                row.getLienholder(),
                row.isLive());
    }

    /**
     * A lot of a producer's grain going into storage.
     *
     * @param lotId the lot, one to 24 characters
     * @param partyCode whose grain it is, one to 24 characters
     * @param commodityCode what it is, one to eight characters
     * @param locationCode the site, one to 12 characters
     * @param bushels how much
     * @param storedOn the day it went in
     * @param deferredPrice whether it is the house's grain waiting on a price rather than storage; not
     *     unless the caller says so
     */
    public record StoreIn(
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate storedOn,
            boolean deferredPrice) {

        public StoreIn {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(locationCode, "locationCode");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(storedOn, "storedOn");
        }

        /** A lot as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static StoreIn from(JsonNode json) {
            return Body.read(json, StoreIn::read);
        }

        static StoreIn read(Body in) {
            String lotId = in.text("lot_id", 1, 24);
            String partyCode = in.text("party_code", 1, 24);
            String commodityCode = in.text("commodity_code", 1, 8);
            String locationCode = in.text("location_code", 1, 12);
            BigDecimal bushels = in.quantity("bushels");
            LocalDate storedOn = in.date("stored_on");
            boolean deferredPrice = in.flag("deferred_price", false);
            return in.build(
                    () -> new StoreIn(lotId, partyCode, commodityCode, locationCode, bushels, storedOn, deferredPrice));
        }
    }

    /**
     * Bushels going out of a producer's lots.
     *
     * @param partyCode whose grain, one to 24 characters
     * @param commodityCode what grain, one to eight characters
     * @param bushels how much
     * @param withdrawnOn the day it went
     * @param reason why it went; {@code settled} unless the caller says otherwise
     * @param settlementNumber the statement that paid for it, if one did
     * @param locationCode only lots at this site; every site when there is none
     */
    public record WithdrawIn(
            String partyCode,
            String commodityCode,
            BigDecimal bushels,
            LocalDate withdrawnOn,
            String reason,
            Optional<String> settlementNumber,
            Optional<String> locationCode) {

        public WithdrawIn {
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(withdrawnOn, "withdrawnOn");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(settlementNumber, "settlementNumber");
            Objects.requireNonNull(locationCode, "locationCode");
        }

        /** A withdrawal as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static WithdrawIn from(JsonNode json) {
            return Body.read(json, WithdrawIn::read);
        }

        static WithdrawIn read(Body in) {
            String partyCode = in.text("party_code", 1, 24);
            String commodityCode = in.text("commodity_code", 1, 8);
            BigDecimal bushels = in.quantity("bushels");
            LocalDate withdrawnOn = in.date("withdrawn_on");
            String reason = in.text("reason", WithdrawalOptions.SETTLED);
            Optional<String> settlementNumber = in.optionalText("settlement_number");
            Optional<String> locationCode = in.optionalText("location_code");
            return in.build(() -> new WithdrawIn(
                    partyCode, commodityCode, bushels, withdrawnOn, reason, settlementNumber, locationCode));
        }
    }

    /**
     * A warehouse receipt to write against a lot.
     *
     * @param receiptNumber the receipt, one to 24 characters
     * @param lotId the lot it is written for, one to 24 characters
     * @param grade the grade it certifies, one to eight characters
     * @param issuedOn the day it is written
     * @param lienholder whoever holds a lien on the grain, if anybody does
     */
    public record ReceiptIn(
            String receiptNumber, String lotId, String grade, LocalDate issuedOn, Optional<String> lienholder) {

        public ReceiptIn {
            Objects.requireNonNull(receiptNumber, "receiptNumber");
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(grade, "grade");
            Objects.requireNonNull(issuedOn, "issuedOn");
            Objects.requireNonNull(lienholder, "lienholder");
        }

        /** A receipt as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ReceiptIn from(JsonNode json) {
            return Body.read(json, ReceiptIn::read);
        }

        static ReceiptIn read(Body in) {
            String receiptNumber = in.text("receipt_number", 1, 24);
            String lotId = in.text("lot_id", 1, 24);
            String grade = in.text("grade", 1, 8);
            LocalDate issuedOn = in.date("issued_on");
            Optional<String> lienholder = in.optionalText("lienholder");
            return in.build(() -> new ReceiptIn(receiptNumber, lotId, grade, issuedOn, lienholder));
        }
    }

    /**
     * A storage lot, as it stands.
     *
     * @param lotId the lot
     * @param partyCode whose grain it is
     * @param commodityCode what it is
     * @param locationCode the site
     * @param bushels what went in
     * @param openBushels what is still in it
     * @param storedOn the day it went in
     * @param closedOn the day it emptied, when it has
     * @param billedThrough the day storage has been billed to, when it has been billed at all
     * @param accruedToDate the storage billed so far
     * @param isDeferredPrice whether it is the house's grain waiting on a price rather than storage
     */
    public record LotOut(
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            BigDecimal openBushels,
            LocalDate storedOn,
            Optional<LocalDate> closedOn,
            Optional<LocalDate> billedThrough,
            BigDecimal accruedToDate,
            boolean isDeferredPrice) {

        public LotOut {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(closedOn, "closedOn");
            Objects.requireNonNull(billedThrough, "billedThrough");
        }
    }

    /**
     * What one lot accrued on a run.
     *
     * @param lotId the lot
     * @param partyCode whose grain it is
     * @param commodityCode what it is
     * @param openBushels what was still in it
     * @param fromDay the day it was billed to before; none for a lot never billed, charged from receipt
     * @param through the day it is billed to now
     * @param dollars the storage charged for the span
     */
    public record AccrualLineOut(
            String lotId,
            String partyCode,
            String commodityCode,
            BigDecimal openBushels,
            Optional<LocalDate> fromDay,
            LocalDate through,
            BigDecimal dollars) {

        public AccrualLineOut {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(fromDay, "fromDay");
        }
    }

    /**
     * One accrual run.
     *
     * @param through the day it billed to
     * @param total everything it charged
     * @param recorded whether it was kept, or was a dry run
     * @param lines what each lot accrued
     */
    public record AccrualOut(LocalDate through, BigDecimal total, boolean recorded, List<AccrualLineOut> lines) {

        public AccrualOut {
            Objects.requireNonNull(through, "through");
            lines = List.copyOf(lines);
        }
    }

    /**
     * A warehouse receipt, as it stands.
     *
     * @param receiptNumber the receipt
     * @param partyCode whose grain it covers
     * @param commodityCode what grain
     * @param locationCode the site
     * @param bushels the bushels it covers
     * @param grade the grade it certifies
     * @param issuedOn the day it was written
     * @param cancelledOn the day it was cancelled, when it has been
     * @param lienholder whoever holds a lien on the grain, when anybody does
     * @param isLive whether it still stands
     */
    public record ReceiptOut(
            String receiptNumber,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            String grade,
            LocalDate issuedOn,
            Optional<LocalDate> cancelledOn,
            Optional<String> lienholder,
            boolean isLive) {

        public ReceiptOut {
            Objects.requireNonNull(receiptNumber, "receiptNumber");
            Objects.requireNonNull(cancelledOn, "cancelledOn");
            Objects.requireNonNull(lienholder, "lienholder");
        }
    }
}
