package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.SampleReading;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinReadingRow;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import io.github.rkaushikdev.bushelworks.reports.binsheet.BinLine;
import io.github.rkaushikdev.bushelworks.reports.binsheet.BinSheet;
import io.github.rkaushikdev.bushelworks.services.binyard.BinYard;
import io.github.rkaushikdev.bushelworks.services.binyard.Placed;
import io.github.rkaushikdev.bushelworks.services.binyard.ReadingOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Bin yard routes, the loader operator's screen, so a scale key is enough.
 *
 * <p>Putting a load away is a POST that may or may not name a bin: when it does not, the yard picks
 * the one that gets finished first, which is the rule nobody wants to remember at seven in the
 * morning.
 *
 * <p>The reports sit under {@code report} rather than beside the bins at {@code
 * /bins/{location}/summary}. That two segment path is already taken by the bin lookup, which answered
 * it as a bin called "summary" and returned a 404 nobody could explain.
 */
@RestController
@RequestMapping("/bins")
@Requires(Role.SCALE)
@Tag(name = "bins")
public class BinRoutes {

    /** The commodity the yard summary reports on when the caller does not say. */
    public static final String SUMMARY_COMMODITY = "CORN";

    private final Work work;
    private final Settings settings;

    public BinRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    @GetMapping("/{location_code}")
    @Operation(summary = "List Bins")
    public List<BinOut> listBins(
            @PathVariable("location_code") String locationCode,
            @RequestParam(name = "commodity_code", required = false) String commodityCode) {
        return work.apply(uow -> BinYard.binsAt(uow, locationCode, commodityCode).stream()
                .map(BinRoutes::binOut)
                .toList());
    }

    @GetMapping("/{location_code}/{bin_id}")
    @Operation(summary = "Read Bin")
    public BinOut readBin(@PathVariable("location_code") String locationCode, @PathVariable("bin_id") String binId) {
        return work.apply(uow -> binOut(BinYard.binAt(uow, locationCode, binId)));
    }

    @PostMapping("/{location_code}/loads")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Put Load Away")
    public BinOut putLoadAway(@PathVariable("location_code") String locationCode, @RequestBody PutAwayIn body) {
        return work.apply(uow -> {
            Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
            body.sample().forEach(reading -> sample.put(reading.factor(), reading.value()));
            Placed placed = BinYard.putAway(
                    uow,
                    locationCode,
                    body.commodityCode(),
                    body.bushels(),
                    sample,
                    body.binId().orElse(null));
            BinOut out = binOut(BinYard.binAt(uow, locationCode, placed.binId()));
            uow.commit();
            return out;
        });
    }

    @PostMapping("/{location_code}/{bin_id}/draws")
    @Operation(summary = "Draw")
    public BinOut draw(
            @PathVariable("location_code") String locationCode,
            @PathVariable("bin_id") String binId,
            @RequestParam("bushels") String bushels) {
        return work.apply(uow -> {
            BinOut out = binOut(BinYard.drawFrom(uow, locationCode, binId, Decimals.parse(bushels)));
            uow.commit();
            return out;
        });
    }

    /** Log a cable reading, and say whether it found the bin working. */
    @PostMapping("/{location_code}/{bin_id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add Reading")
    public Map<String, String> addReading(
            @PathVariable("location_code") String locationCode,
            @PathVariable("bin_id") String binId,
            @RequestBody CableIn body) {
        return work.apply(uow -> {
            ReadingOptions options = ReadingOptions.defaults()
                    .withCable(body.cable())
                    .withMoisture(body.moisture().orElse(null))
                    .withFanRunning(body.fanRunning())
                    .withNote(body.note().orElse(null));
            BinReadingRow reading =
                    BinYard.recordReading(uow, locationCode, binId, body.takenAt(), body.temperatureC(), options);
            boolean warm = reading.isWarm();
            uow.commit();
            Map<String, String> out = new LinkedHashMap<>();
            out.put("bin_id", binId);
            out.put("warm", warm ? "yes" : "no");
            return out;
        });
    }

    @PostMapping("/{location_code}/{bin_id}/out-of-service")
    @Operation(summary = "Out Of Service")
    public BinOut outOfService(
            @PathVariable("location_code") String locationCode,
            @PathVariable("bin_id") String binId,
            @RequestParam("reason")
                    @Size(min = 1, message = "String should have at least 1 character")
                    @Size(max = 120, message = "String should have at most 120 characters")
                    String reason,
            @RequestParam(name = "on", required = false) LocalDate on) {
        return work.apply(uow -> {
            BinOut out = binOut(
                    BinYard.takeOutOfService(uow, locationCode, binId, reason, on == null ? LocalDate.now() : on));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/{location_code}/report/sheet")
    @Operation(summary = "Print Sheet")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE))
    public ResponseEntity<String> printSheet(
            @PathVariable("location_code") String locationCode,
            @RequestParam(name = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime since) {
        String sheet =
                work.apply(uow -> BinSheet.render(lines(uow, locationCode, since), locationCode, settings.houseName()));
        return ResponseEntity.ok().contentType(PositionRoutes.TEXT).body(sheet);
    }

    @GetMapping("/{location_code}/report/export")
    @Operation(summary = "Export Sheet")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv"))
    public ResponseEntity<String> exportSheet(
            @PathVariable("location_code") String locationCode,
            @RequestParam(name = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime since) {
        String csv = work.apply(uow -> BinSheet.toCsv(lines(uow, locationCode, since)));
        return ResponseEntity.ok()
                .contentType(PositionRoutes.CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bins-" + locationCode + ".csv\"")
                .body(csv);
    }

    @GetMapping("/{location_code}/report/summary")
    @Operation(
            summary = "Read Summary",
            description = "What is in the yard, what room is left and what needs looking at.")
    public Map<String, String> readSummary(
            @PathVariable("location_code") String locationCode,
            @RequestParam(name = "commodity_code", defaultValue = SUMMARY_COMMODITY) String commodityCode,
            @RequestParam(name = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime since) {
        return work.apply(uow -> {
            List<BinLine> lines = lines(uow, locationCode, since);
            List<BinRow> warm = since == null ? List.of() : BinYard.warmBins(uow, locationCode, since);
            Map<String, String> out = new LinkedHashMap<>();
            out.put("location_code", locationCode);
            out.put("commodity_code", commodityCode);
            out.put(
                    "bushels",
                    BinYard.yardTotal(uow, locationCode, commodityCode).toString());
            out.put("room", BinSheet.roomFor(lines, commodityCode).toString());
            out.put("warm", warm.stream().map(BinRow::getBinId).collect(Collectors.joining(",")));
            return out;
        });
    }

    private static List<BinLine> lines(UnitOfWork uow, String locationCode, OffsetDateTime since) {
        return BinYard.binsAt(uow, locationCode).stream()
                .map(row -> BinSheet.lineFor(row, since))
                .toList();
    }

    private static BinOut binOut(BinRow row) {
        return new BinOut(
                row.getBinId(),
                row.getLocationCode(),
                row.getCommodityCode(),
                row.getBushels(),
                row.getCapacityBushels(),
                BinYard.workingHeadroom(row),
                row.getMoisture(),
                row.getTestWeight(),
                row.isOutOfService(),
                row.getOutOfServiceReason());
    }

    /**
     * A load going into the yard.
     *
     * @param commodityCode what it is, one to eight characters
     * @param bushels how much
     * @param binId the bin to put it in; the yard chooses when there is none
     * @param sample what the load tested, which the bin's readings are blended with
     */
    public record PutAwayIn(
            String commodityCode, BigDecimal bushels, Optional<String> binId, List<SampleReading> sample) {

        public PutAwayIn {
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(bushels, "bushels");
            Objects.requireNonNull(binId, "binId");
            sample = List.copyOf(sample);
        }

        /** A load as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PutAwayIn from(JsonNode json) {
            return Body.read(json, PutAwayIn::read);
        }

        static PutAwayIn read(Body in) {
            String commodityCode = in.text("commodity_code", 1, 8);
            BigDecimal bushels = in.quantity("bushels");
            Optional<String> binId = in.optionalText("bin_id");
            List<SampleReading> sample = in.list("sample", SampleReading::read);
            return in.build(() -> new PutAwayIn(commodityCode, bushels, binId, sample));
        }
    }

    /**
     * One reading off a bin's temperature cable.
     *
     * @param takenAt when it was taken; a moment written without an offset is read as UTC
     * @param temperatureC what the cable read, in degrees Celsius
     * @param cable which cable, the first unless the caller says otherwise
     * @param moisture the moisture it read, when the cable reads one
     * @param fanRunning whether the aeration fan was running; not unless the caller says so
     * @param note anything the operator wrote against it
     */
    public record CableIn(
            OffsetDateTime takenAt,
            BigDecimal temperatureC,
            int cable,
            Optional<BigDecimal> moisture,
            boolean fanRunning,
            Optional<String> note) {

        public CableIn {
            Objects.requireNonNull(takenAt, "takenAt");
            Objects.requireNonNull(temperatureC, "temperatureC");
            Objects.requireNonNull(moisture, "moisture");
            Objects.requireNonNull(note, "note");
        }

        /** A reading as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static CableIn from(JsonNode json) {
            return Body.read(json, CableIn::read);
        }

        static CableIn read(Body in) {
            OffsetDateTime takenAt = in.moment("taken_at");
            BigDecimal temperatureC = in.quantity("temperature_c");
            int cable = in.whole("cable", 1, Integer.MIN_VALUE, Integer.MAX_VALUE);
            Optional<BigDecimal> moisture = in.nullableQuantity("moisture");
            boolean fanRunning = in.flag("fan_running", false);
            Optional<String> note = in.optionalText("note");
            return in.build(() -> new CableIn(takenAt, temperatureC, cable, moisture, fanRunning, note));
        }
    }

    /**
     * One bin, as it stands.
     *
     * @param binId the bin
     * @param locationCode the site
     * @param commodityCode what it holds, when it holds anything
     * @param bushels how much it holds
     * @param capacityBushels how much it holds full
     * @param headroom the room left in it, less the room kept free for turning
     * @param moisture the blend's moisture, when it has been tested
     * @param testWeight the blend's test weight, when it has been tested
     * @param outOfService whether it is out of service
     * @param outOfServiceReason why, when it is
     */
    public record BinOut(
            String binId,
            String locationCode,
            Optional<String> commodityCode,
            BigDecimal bushels,
            BigDecimal capacityBushels,
            BigDecimal headroom,
            Optional<BigDecimal> moisture,
            Optional<BigDecimal> testWeight,
            boolean outOfService,
            Optional<String> outOfServiceReason) {

        public BinOut {
            Objects.requireNonNull(binId, "binId");
            Objects.requireNonNull(commodityCode, "commodityCode");
            Objects.requireNonNull(moisture, "moisture");
            Objects.requireNonNull(testWeight, "testWeight");
            Objects.requireNonNull(outOfServiceReason, "outOfServiceReason");
        }
    }
}
