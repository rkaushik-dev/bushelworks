package io.github.rkaushikdev.bushelworks.api.routes;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.reports.position.PositionBoard;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Position routes.
 *
 * <p>Read only. Nothing here changes anything, which is why nothing here commits, why a read key is
 * enough, and why the manager's screen can poll it every thirty seconds without anybody worrying.
 */
@RestController
@RequestMapping("/positions")
@Requires(Role.READ)
@Tag(name = "positions")
public class PositionRoutes {

    /** A futures contract's size in bushels, when the caller does not say. */
    public static final String CONTRACT_SIZE = "5000";

    static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final Work work;
    private final Settings settings;

    public PositionRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    @GetMapping("/{as_of}")
    @Operation(summary = "Read Position")
    public PositionOut readPosition(
            @PathVariable("as_of") LocalDate asOf,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode,
            @RequestParam(name = "contract_size", defaultValue = CONTRACT_SIZE) BigDecimal contractSize) {
        return work.apply(uow -> {
            PositionReport report = report(uow, asOf, locationCode);
            return new PositionOut(
                    report.asOf(),
                    report.totalOwned(),
                    report.lines().stream()
                            .map(line -> lineOut(line, contractSize))
                            .toList());
        });
    }

    /**
     * One commodity, assembled whole from what is on file.
     *
     * <p>{@code in_transit} is the one figure still passed in. Grain on a barge between two of the
     * house's own sites has no document here to read it off.
     */
    @GetMapping("/{as_of}/commodity/{commodity_code}")
    @Operation(summary = "Read Commodity Position")
    public PositionLineOut readCommodityPosition(
            @PathVariable("as_of") LocalDate asOf,
            @PathVariable("commodity_code") String commodityCode,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode,
            @RequestParam(name = "in_transit", defaultValue = "0") String inTransit,
            @RequestParam(name = "contract_size", defaultValue = CONTRACT_SIZE) BigDecimal contractSize) {
        return work.apply(uow -> lineOut(
                Positions.lineFor(uow, commodityCode, asOf, locationCode, Decimals.parse(inTransit)), contractSize));
    }

    @GetMapping("/{as_of}/board")
    @Operation(summary = "Print Board")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printBoard(
            @PathVariable("as_of") LocalDate asOf,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode) {
        String board = work.apply(uow -> PositionBoard.render(report(uow, asOf, locationCode), settings.houseName()));
        return ResponseEntity.ok().contentType(TEXT).body(board);
    }

    @GetMapping("/{as_of}/hedge-sheet")
    @Operation(summary = "Print Hedge Sheet")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printHedgeSheet(
            @PathVariable("as_of") LocalDate asOf,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode,
            @RequestParam(name = "contract_size", defaultValue = CONTRACT_SIZE) BigDecimal contractSize) {
        String sheet = work.apply(uow -> PositionBoard.hedgeSheet(report(uow, asOf, locationCode), contractSize));
        return ResponseEntity.ok().contentType(TEXT).body(sheet);
    }

    @GetMapping("/{as_of}/export")
    @Operation(summary = "Export Position")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv"))
    public ResponseEntity<String> exportPosition(
            @PathVariable("as_of") LocalDate asOf,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode) {
        String csv = work.apply(uow -> PositionBoard.toCsv(report(uow, asOf, locationCode)));
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"position-" + asOf + ".csv\"")
                .body(csv);
    }

    /**
     * The gap between the book and what the broker says is on.
     *
     * <p>Hedged quantities are not on file here, so this reports the whole position as unhedged until
     * somebody feeds the broker statement in. That is the honest answer and it is deliberately
     * uncomfortable.
     */
    @GetMapping("/{as_of}/unhedged")
    @Operation(summary = "Read Unhedged")
    public Map<String, String> readUnhedged(
            @PathVariable("as_of") LocalDate asOf,
            @RequestParam(name = "location_code", defaultValue = Positions.EVERY_SITE) String locationCode) {
        return work.apply(uow -> {
            Map<String, String> gaps = new LinkedHashMap<>();
            Positions.unhedged(report(uow, asOf, locationCode), Map.of())
                    .forEach((code, gap) -> gaps.put(code, gap.toString()));
            return gaps;
        });
    }

    private static PositionReport report(UnitOfWork uow, LocalDate asOf, String locationCode) {
        return Positions.buildReport(uow, asOf, List.of(), locationCode);
    }

    private static PositionLineOut lineOut(PositionLine line, BigDecimal contractSize) {
        return new PositionLineOut(
                line.asOf(),
                line.commodityCode(),
                line.locationCode(),
                line.onHand(),
                line.storageObligation(),
                line.deferredPrice(),
                line.purchasedForward(),
                line.soldForward(),
                line.inTransit(),
                line.owned(),
                line.netLong(),
                line.isShort(),
                io.github.rkaushikdev.bushelworks.domain.position.Positions.hedgeRequirement(line, contractSize));
    }

    /**
     * One commodity's position on one day.
     *
     * @param asOf the day
     * @param commodityCode the commodity
     * @param locationCode the site, or {@code *} for the whole house
     * @param onHand bushels in the bins
     * @param storageObligation bushels in the bins that belong to producers
     * @param deferredPrice bushels delivered and not yet priced
     * @param purchasedForward bushels bought on contract and not yet delivered
     * @param soldForward bushels sold on contract and not yet shipped
     * @param inTransit bushels on the way between the house's own sites
     * @param owned the house's own bushels in the bins
     * @param netLong what the house stands to gain or lose on a price move, in bushels
     * @param isShort whether the house is short
     * @param hedgeContracts the futures contracts the net long calls for, rounded toward zero
     */
    public record PositionLineOut(
            LocalDate asOf,
            String commodityCode,
            String locationCode,
            BigDecimal onHand,
            BigDecimal storageObligation,
            BigDecimal deferredPrice,
            BigDecimal purchasedForward,
            BigDecimal soldForward,
            BigDecimal inTransit,
            BigDecimal owned,
            BigDecimal netLong,
            boolean isShort,
            int hedgeContracts) {

        public PositionLineOut {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(commodityCode, "commodityCode");
        }
    }

    /**
     * The whole board on one day.
     *
     * @param asOf the day
     * @param totalOwned the house's own bushels across every commodity
     * @param lines one per commodity with anything on hand or bought forward
     */
    public record PositionOut(LocalDate asOf, BigDecimal totalOwned, List<PositionLineOut> lines) {

        public PositionOut {
            Objects.requireNonNull(asOf, "asOf");
            lines = List.copyOf(lines);
        }
    }
}
