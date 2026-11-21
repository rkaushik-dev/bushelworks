package io.github.rkaushikdev.bushelworks.api.routes;

import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageBoard;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageError;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageLine;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageYear;
import io.github.rkaushikdev.bushelworks.services.patronage.CropYear;
import io.github.rkaushikdev.bushelworks.services.patronage.Patronage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Board reports, for the office.
 *
 * <p>Everything here is a read and everything here is slow. The patronage table walks every
 * settlement in a crop year, which on a real house is thirty thousand rows, so these are not routes
 * anybody should poll. The office runs them once in September and prints the answer.
 *
 * <p>Two shapes come back off the same data: {@code .../table} is the eighty column page that goes
 * in the board pack, and {@code .../export} is the CSV the accountant opens. Neither is computed
 * here. Both come out of {@link PatronageBoard}, so a number on the printed page and the same number
 * in the spreadsheet cannot drift apart.
 *
 * <p>Every route takes a crop year, or a span named by both its ends. The membership roll is not in
 * this database: it arrives as a repeated {@code member} query parameter, which is ugly with four
 * hundred members and correct with all of them, and the alternative was a second roll that goes
 * stale. With no roll, everybody is a member.
 */
@RestController
@RequestMapping("/reports")
@Requires(Role.OFFICE)
@Tag(name = "reports")
public class ReportRoutes {

    private final Work work;

    public ReportRoutes(Work work) {
        this.work = work;
    }

    @GetMapping("/patronage")
    @Operation(summary = "Read Patronage", description = "The whole table, one line per producer who settled.")
    public PatronageOut readPatronage(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "opens", required = false) LocalDate opens,
            @RequestParam(name = "closes", required = false) LocalDate closes,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "member", required = false) List<String> member) {
        return work.apply(uow -> patronageOut(built(uow, year, opens, closes, locationCode, member)));
    }

    @GetMapping("/patronage/table")
    @Operation(summary = "Print Patronage", description = "The page that goes in the board pack, eighty columns wide.")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printPatronage(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "opens", required = false) LocalDate opens,
            @RequestParam(name = "closes", required = false) LocalDate closes,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "order", defaultValue = PatronageBoard.BY_BUSHELS) String order,
            @RequestParam(name = "limit", defaultValue = "25") int limit,
            @RequestParam(name = "member", required = false) List<String> member) {
        String page = work.apply(
                uow -> PatronageBoard.render(built(uow, year, opens, closes, locationCode, member), order, limit));
        return ResponseEntity.ok().contentType(PositionRoutes.TEXT).body(page);
    }

    @GetMapping("/patronage/export")
    @Operation(summary = "Export Patronage")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv"))
    public ResponseEntity<String> exportPatronage(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "opens", required = false) LocalDate opens,
            @RequestParam(name = "closes", required = false) LocalDate closes,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "member", required = false) List<String> member) {
        PatronageYear built = work.apply(uow -> built(uow, year, opens, closes, locationCode, member));
        Object stamp = year != null ? year : built.opens();
        return ResponseEntity.ok()
                .contentType(PositionRoutes.CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"patronage-" + stamp + ".csv\"")
                .body(PatronageBoard.toCsv(built));
    }

    @GetMapping("/patronage/allocation")
    @Operation(
            summary = "Read Allocation",
            description = "What each member would get out of a pool, before the board votes. Shares under the"
                    + " minimum are dropped rather than rounded up, so the total coming back is usually a"
                    + " little under the pool: that difference is the unallocated remainder, and it is meant"
                    + " to be visible.")
    public Map<String, String> readAllocation(
            @RequestParam("pool") BigDecimal pool,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "opens", required = false) LocalDate opens,
            @RequestParam(name = "closes", required = false) LocalDate closes,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "minimum", defaultValue = "5.00") BigDecimal minimum,
            @RequestParam(name = "member", required = false) List<String> member) {
        return work.apply(uow -> {
            PatronageYear built = built(uow, year, opens, closes, locationCode, member);
            Map<String, String> out = new LinkedHashMap<>();
            Patronage.allocate(built, pool, minimum).forEach((code, share) -> out.put(code, share.toString()));
            return out;
        });
    }

    /**
     * The year a caller asked for: a crop year, or a span the caller named by both ends. A year given
     * wins over a span.
     *
     * @throws PatronageError when there is neither a year nor both ends of a span
     */
    private static PatronageYear built(
            UnitOfWork uow,
            Integer year,
            LocalDate opens,
            LocalDate closes,
            String locationCode,
            List<String> members) {
        if (year != null) {
            CropYear span = Patronage.cropYear(year);
            return Patronage.buildYear(uow, span.opens(), span.closes(), locationCode, members);
        }
        if (opens == null || closes == null) {
            throw new PatronageError("give a crop year, or both ends of a span");
        }
        return Patronage.buildYear(uow, opens, closes, locationCode, members);
    }

    private static PatronageOut patronageOut(PatronageYear built) {
        return new PatronageOut(
                built.locationCode(),
                built.opens(),
                built.closes(),
                built.bushels(),
                built.gross(),
                built.margin(),
                built.concentration(),
                built.byBushels().stream()
                        .map(line -> lineOut(line, built.shareOfBushels(line.partyCode())))
                        .toList());
    }

    private static PatronageLineOut lineOut(PatronageLine line, BigDecimal sharePercent) {
        return new PatronageLineOut(
                line.partyCode(),
                line.name(),
                line.isMember(),
                line.loads(),
                line.bushels(),
                line.gross(),
                line.margin(),
                line.marginPerBushel(),
                sharePercent);
    }

    /**
     * One producer's line on the patronage table.
     *
     * @param partyCode the producer
     * @param name the producer's name off the party file
     * @param isMember whether the producer is on the membership roll
     * @param loads how many loads the bushels came in on
     * @param bushels the bushels settled
     * @param gross the gross dollars on the producer's statements
     * @param margin what the house kept off them
     * @param marginPerBushel the margin over the bushels
     * @param sharePercent the producer's share of the year's bushels, as a percentage
     */
    public record PatronageLineOut(
            String partyCode,
            String name,
            boolean isMember,
            int loads,
            BigDecimal bushels,
            BigDecimal gross,
            BigDecimal margin,
            BigDecimal marginPerBushel,
            BigDecimal sharePercent) {

        public PatronageLineOut {
            Objects.requireNonNull(partyCode, "partyCode");
        }
    }

    /**
     * The patronage table for a year, biggest producer first.
     *
     * @param locationCode the site, or {@code *} for every site
     * @param opens the first day of the year
     * @param closes the last day of the year
     * @param bushels every producer's bushels
     * @param gross every producer's gross dollars
     * @param margin what the house kept off all of them
     * @param concentrationPercent what share of the bushels the ten biggest producers account for
     * @param lines one line per producer who settled, the most bushels first
     */
    public record PatronageOut(
            String locationCode,
            LocalDate opens,
            LocalDate closes,
            BigDecimal bushels,
            BigDecimal gross,
            BigDecimal margin,
            BigDecimal concentrationPercent,
            List<PatronageLineOut> lines) {

        public PatronageOut {
            Objects.requireNonNull(locationCode, "locationCode");
            lines = List.copyOf(lines);
        }
    }
}
