package io.github.rkaushikdev.bushelworks.api.routes;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Entry;
import io.github.rkaushikdev.bushelworks.services.dayend.Close;
import io.github.rkaushikdev.bushelworks.services.dayend.CloseOptions;
import io.github.rkaushikdev.bushelworks.services.dayend.DayEnd;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit and day close routes, for the office.
 *
 * <p>Read only except for the close, which is a POST because running it moves what each lot has been
 * billed to. The audit trail has no write route at all: entries are written by the services that do
 * the thing being recorded, and a route that could write one directly would let somebody put a lie
 * in the evidence.
 */
@RestController
@RequestMapping("/ops")
@Requires(Role.OFFICE)
@Tag(name = "ops")
public class OpsRoutes {

    /** How far a commodity's net long may move overnight before the close asks about it, by default. */
    public static final String POSITION_TOLERANCE = "250000";

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private final Work work;
    private final Settings settings;

    public OpsRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    /**
     * Everything that ever happened to one document, oldest first.
     *
     * <p>The route is under its own segment rather than at the top of {@code /audit}. A path of
     * {@code /audit/{kind}/{number}} would swallow {@code /audit/party/X} and answer it as a document
     * of kind "party", which it once silently did.
     */
    @GetMapping("/audit/documents/{document_kind}/{document_number}")
    @Operation(
            summary = "Read Document Trail",
            description = "Everything that ever happened to one document, oldest first.")
    public List<EntryOut> readDocumentTrail(
            @PathVariable("document_kind") String documentKind,
            @PathVariable("document_number") String documentNumber) {
        return work.apply(uow -> Audit.forDocument(uow, documentKind, documentNumber).stream()
                .map(OpsRoutes::entryOut)
                .toList());
    }

    @GetMapping("/audit/party/{party_code}")
    @Operation(summary = "Read Party Trail", description = "A producer's trail, newest first.")
    public List<EntryOut> readPartyTrail(
            @PathVariable("party_code") String partyCode,
            @RequestParam(name = "limit", defaultValue = "200")
                    @Min(value = 1, message = "Input should be greater than or equal to 1")
                    @Max(value = 1000, message = "Input should be less than or equal to 1000")
                    int limit) {
        return work.apply(uow -> Audit.forParty(uow, partyCode, limit).stream()
                .map(OpsRoutes::entryOut)
                .toList());
    }

    @GetMapping("/audit/day/{day}")
    @Operation(summary = "Read Day Trail")
    public List<EntryOut> readDayTrail(
            @PathVariable("day") LocalDate day, @RequestParam(name = "action", required = false) String action) {
        return work.apply(uow ->
                Audit.forDay(uow, day, action).stream().map(OpsRoutes::entryOut).toList());
    }

    @PostMapping("/close/{day}")
    @Operation(
            summary = "Close Day",
            description = "Close a day. The accrual side of it commits; the checks do not write.")
    public CloseOut closeDay(
            @PathVariable("day") LocalDate day,
            @RequestParam(name = "location_code", defaultValue = CloseOptions.EVERY_SITE) String locationCode,
            @RequestParam(name = "position_tolerance", defaultValue = POSITION_TOLERANCE)
                    BigDecimal positionTolerance) {
        return work.apply(uow -> {
            CloseOut out = closeOut(DayEnd.runClose(uow, day, options(locationCode, positionTolerance)));
            uow.commit();
            return out;
        });
    }

    /** The close as it goes in the day book. Run in full to print it, and none of it kept. */
    @GetMapping("/close/{day}/sheet")
    @Operation(summary = "Print Close")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printClose(
            @PathVariable("day") LocalDate day,
            @RequestParam(name = "location_code", defaultValue = CloseOptions.EVERY_SITE) String locationCode,
            @RequestParam(name = "position_tolerance", defaultValue = POSITION_TOLERANCE)
                    BigDecimal positionTolerance) {
        String sheet = work.apply(uow -> DayEnd.render(
                DayEnd.runClose(uow, day, options(locationCode, positionTolerance)), settings.houseName()));
        return ResponseEntity.ok().contentType(PositionRoutes.TEXT).body(sheet);
    }

    private static CloseOptions options(String locationCode, BigDecimal positionTolerance) {
        return CloseOptions.defaults().withLocationCode(locationCode).withPositionTolerance(positionTolerance);
    }

    /**
     * When an entry was recorded, as ISO 8601 text in UTC with the offset written out: {@code
     * 2024-10-03T05:31:02.250000+00:00}, the fraction in microseconds and left off when there is none.
     */
    static String recordedAt(OffsetDateTime at) {
        OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
        int micros = utc.getNano() / 1_000;
        String fraction = micros == 0 ? "" : String.format(Locale.ROOT, ".%06d", micros);
        return utc.format(SECONDS) + fraction + "+00:00";
    }

    private static EntryOut entryOut(Entry entry) {
        return new EntryOut(
                recordedAt(entry.recordedAt()),
                entry.actor(),
                entry.action(),
                entry.documentKind(),
                entry.documentNumber(),
                entry.partyCode(),
                entry.amount(),
                entry.detail());
    }

    private static CloseOut closeOut(Close close) {
        return new CloseOut(
                close.day(),
                close.locationCode(),
                close.ticketsWritten(),
                close.ticketsVoided(),
                close.bushelsIn(),
                close.bushelsOut(),
                close.netBushels(),
                close.settlementsWritten(),
                close.settledValue(),
                close.isClean(),
                close.discrepancies().stream()
                        .map(item -> new DiscrepancyOut(item.kind(), item.detail()))
                        .toList());
    }

    /**
     * One audit entry.
     *
     * @param recordedAt when it was written, see {@link #recordedAt}
     * @param actor who did it
     * @param action what was done
     * @param documentKind the kind of document it was done to
     * @param documentNumber the document's number
     * @param partyCode the producer it concerned, when there was one
     * @param amount the money, as it stood, when there was any
     * @param detail what else was said
     */
    public record EntryOut(
            String recordedAt,
            String actor,
            String action,
            String documentKind,
            String documentNumber,
            Optional<String> partyCode,
            Optional<BigDecimal> amount,
            Optional<String> detail) {

        public EntryOut {
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * Something the close found that wants a person to look at it.
     *
     * @param kind what kind of thing
     * @param detail what it was
     */
    public record DiscrepancyOut(String kind, String detail) {}

    /**
     * What a day's close came to.
     *
     * @param day the day
     * @param locationCode the site, or {@code *} for the whole house
     * @param ticketsWritten tickets written that day
     * @param ticketsVoided tickets voided that day
     * @param bushelsIn bushels received
     * @param bushelsOut bushels shipped
     * @param netBushels in less out
     * @param settlementsWritten settlements written that day
     * @param settledValue what they paid
     * @param isClean whether the close found nothing to look at
     * @param discrepancies what it found
     */
    public record CloseOut(
            LocalDate day,
            String locationCode,
            int ticketsWritten,
            int ticketsVoided,
            BigDecimal bushelsIn,
            BigDecimal bushelsOut,
            BigDecimal netBushels,
            int settlementsWritten,
            BigDecimal settledValue,
            boolean isClean,
            List<DiscrepancyOut> discrepancies) {

        public CloseOut {
            Objects.requireNonNull(day, "day");
            discrepancies = List.copyOf(discrepancies);
        }
    }
}
