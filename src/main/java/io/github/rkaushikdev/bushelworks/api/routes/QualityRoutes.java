package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.complaints.Complaint;
import io.github.rkaushikdev.bushelworks.domain.complaints.Outcome;
import io.github.rkaushikdev.bushelworks.domain.complaints.Source;
import io.github.rkaushikdev.bushelworks.persistence.models.ComplaintRow;
import io.github.rkaushikdev.bushelworks.services.quality.ComplaintSummary;
import io.github.rkaushikdev.bushelworks.services.quality.FilingOptions;
import io.github.rkaushikdev.bushelworks.services.quality.Quality;
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
 * Complaint routes, for the office.
 *
 * <p>Filing a complaint is a POST and closing one is a POST to its own path rather than a field edit
 * on the complaint. The distinction matters: closing runs the rules about credits and dates, and a
 * general purpose edit that let a caller set the outcome and the credit directly would walk straight
 * past all of them.
 *
 * <p>A complaint names a producer, a credit and sometimes a buyer who refused a car, and none of that
 * belongs on the scale terminal.
 */
@RestController
@RequestMapping("/complaints")
@Requires(Role.OFFICE)
@Tag(name = "complaints")
public class QualityRoutes {

    private final Work work;

    public QualityRoutes(Work work) {
        this.work = work;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Take Complaint",
            description = "File a complaint. A named ticket is checked against the ticket file.")
    public ComplaintOut takeComplaint(@RequestBody ComplaintIn body) {
        return work.apply(uow -> {
            ComplaintRow row = Quality.fileComplaint(
                    uow,
                    body.reference(),
                    body.source(),
                    body.subject(),
                    body.raisedOn(),
                    body.deliveredOn(),
                    FilingOptions.defaults()
                            .withPartyCode(body.partyCode().orElse(null))
                            .withBinId(body.binId().orElse(null))
                            .withTicketNumber(body.ticketNumber().orElse(null))
                            .withSaleNumber(body.saleNumber().orElse(null))
                            .withProbeId(body.probeId().orElse(null)));
            ComplaintOut out = complaintOut(Quality.toDomain(row));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Read Complaint")
    public ComplaintOut readComplaint(@PathVariable("reference") String reference) {
        return work.apply(uow -> complaintOut(Quality.toDomain(uow.complaints().row(reference))));
    }

    @GetMapping
    @Operation(summary = "List Open", description = "Everything still open, oldest first.")
    public List<ComplaintOut> listOpen(@RequestParam(name = "bin_id", required = false) String binId) {
        return work.apply(uow -> Quality.openComplaints(uow, binId).stream()
                .map(QualityRoutes::complaintOut)
                .toList());
    }

    @PostMapping("/{reference}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add Action", description = "Record what was done about it, in the order it was done.")
    public Map<String, String> addAction(@PathVariable("reference") String reference, @RequestBody ActionIn body) {
        return work.apply(uow -> {
            Quality.act(uow, reference, body.takenOn(), body.takenBy(), body.what());
            uow.commit();
            Map<String, String> out = new LinkedHashMap<>();
            out.put("reference", reference);
            out.put("taken_by", body.takenBy());
            out.put("what", body.what());
            return out;
        });
    }

    @PostMapping("/{reference}/close")
    @Operation(
            summary = "Close Complaint",
            description = "Close it. The rules about credits and dates live in the domain object.")
    public ComplaintOut closeComplaint(@PathVariable("reference") String reference, @RequestBody CloseIn body) {
        return work.apply(uow -> {
            ComplaintRow row = Quality.close(uow, reference, body.outcome(), body.on(), body.credit(), body.note());
            ComplaintOut out = complaintOut(Quality.toDomain(row));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/reports/indefensible")
    @Operation(
            summary = "Read Indefensible",
            description = "Open complaints with no retained sample left to argue from.")
    public List<ComplaintOut> readIndefensible(
            @RequestParam("opens") LocalDate opens, @RequestParam("closes") LocalDate closes) {
        return work.apply(uow -> Quality.indefensible(uow, opens, closes).stream()
                .map(QualityRoutes::complaintOut)
                .toList());
    }

    @GetMapping("/reports/repeat-bins")
    @Operation(
            summary = "Read Repeat Bins",
            description = "Bins complained about more often than the house is comfortable with.")
    public List<String> readRepeatBins(
            @RequestParam("opens") LocalDate opens,
            @RequestParam("closes") LocalDate closes,
            @RequestParam(name = "threshold", defaultValue = "3") int threshold) {
        return work.apply(uow -> Quality.repeatBins(uow, opens, closes, threshold));
    }

    @GetMapping("/reports/summary")
    @Operation(summary = "Read Summary", description = "The page the manager looks at once a month.")
    public SummaryOut readSummary(@RequestParam("opens") LocalDate opens, @RequestParam("closes") LocalDate closes) {
        return work.apply(uow -> summaryOut(Quality.summary(uow, opens, closes)));
    }

    private static ComplaintOut complaintOut(Complaint complaint) {
        return new ComplaintOut(
                complaint.reference(),
                complaint.source(),
                complaint.subject(),
                complaint.raisedOn(),
                complaint.deliveredOn(),
                complaint.daysAfter(),
                complaint.partyCode(),
                complaint.binId(),
                complaint.ticketNumber(),
                complaint.saleNumber(),
                complaint.probeId(),
                complaint.outcome(),
                complaint.credit(),
                complaint.closedOn(),
                complaint.note(),
                complaint.isOpen(),
                complaint.isLate(),
                complaint.sampleIsAvailable(Quality.RETENTION_DAYS));
    }

    private static SummaryOut summaryOut(ComplaintSummary summary) {
        return new SummaryOut(
                summary.opens(),
                summary.closes(),
                summary.count(),
                summary.open(),
                summary.late(),
                summary.upheldRate(),
                summary.credits(),
                summary.bySource(),
                summary.indefensible(),
                summary.repeatBins(),
                summary.windowDays());
    }

    /**
     * A complaint as the office takes it down.
     *
     * @param reference the house's reference for it, one to 24 characters
     * @param source who rang
     * @param subject what it is about, one to 200 characters
     * @param raisedOn the day it came in
     * @param deliveredOn the day the grain was delivered
     * @param partyCode who it is, when known, up to 24 characters
     * @param binId the bin the grain came out of, when known, up to 12 characters
     * @param ticketNumber the scale ticket, when there is one, up to 24 characters
     * @param saleNumber the sale, when there is one, up to 24 characters
     * @param probeId the retained sample, when there is one, up to 24 characters
     */
    public record ComplaintIn(
            String reference,
            Source source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn,
            Optional<String> partyCode,
            Optional<String> binId,
            Optional<String> ticketNumber,
            Optional<String> saleNumber,
            Optional<String> probeId) {

        public ComplaintIn {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(raisedOn, "raisedOn");
            Objects.requireNonNull(deliveredOn, "deliveredOn");
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(binId, "binId");
            Objects.requireNonNull(ticketNumber, "ticketNumber");
            Objects.requireNonNull(saleNumber, "saleNumber");
            Objects.requireNonNull(probeId, "probeId");
        }

        /** A complaint as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ComplaintIn from(JsonNode json) {
            return Body.read(json, ComplaintIn::read);
        }

        static ComplaintIn read(Body in) {
            String reference = in.text("reference", 1, 24);
            Source source = in.choice("source", Source.class);
            String subject = in.text("subject", 1, 200);
            LocalDate raisedOn = in.date("raised_on");
            LocalDate deliveredOn = in.date("delivered_on");
            Optional<String> partyCode = in.optionalText("party_code", 24);
            Optional<String> binId = in.optionalText("bin_id", 12);
            Optional<String> ticketNumber = in.optionalText("ticket_number", 24);
            Optional<String> saleNumber = in.optionalText("sale_number", 24);
            Optional<String> probeId = in.optionalText("probe_id", 24);
            return in.build(() -> new ComplaintIn(
                    reference,
                    source,
                    subject,
                    raisedOn,
                    deliveredOn,
                    partyCode,
                    binId,
                    ticketNumber,
                    saleNumber,
                    probeId));
        }
    }

    /**
     * Something done about a complaint.
     *
     * @param takenOn the day it was done
     * @param takenBy who did it, one to 40 characters
     * @param what what they did, one to 200 characters
     */
    public record ActionIn(LocalDate takenOn, String takenBy, String what) {

        public ActionIn {
            Objects.requireNonNull(takenOn, "takenOn");
            Objects.requireNonNull(takenBy, "takenBy");
            Objects.requireNonNull(what, "what");
        }

        /** An action as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ActionIn from(JsonNode json) {
            return Body.read(json, ActionIn::read);
        }

        static ActionIn read(Body in) {
            LocalDate takenOn = in.date("taken_on");
            String takenBy = in.text("taken_by", 1, 40);
            String what = in.text("what", 1, 200);
            return in.build(() -> new ActionIn(takenOn, takenBy, what));
        }
    }

    /**
     * How a complaint ended.
     *
     * @param outcome how it was decided
     * @param on the day it closed
     * @param credit what the house gave back; nothing unless the office says otherwise
     * @param note what the office writes on it, up to 400 characters; none unless given
     */
    public record CloseIn(Outcome outcome, LocalDate on, BigDecimal credit, String note) {

        public CloseIn {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(on, "on");
            Objects.requireNonNull(credit, "credit");
            Objects.requireNonNull(note, "note");
        }

        /** A closing as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static CloseIn from(JsonNode json) {
            return Body.read(json, CloseIn::read);
        }

        static CloseIn read(Body in) {
            Outcome outcome = in.choice("outcome", Outcome.class);
            LocalDate on = in.date("on");
            BigDecimal credit = in.quantity("credit", new BigDecimal("0.00"));
            String note = in.text("note", "", 400);
            return in.build(() -> new CloseIn(outcome, on, credit, note));
        }
    }

    /**
     * A complaint on the register.
     *
     * @param reference the house's reference for it
     * @param source who rang
     * @param subject what it is about
     * @param raisedOn the day it came in
     * @param deliveredOn the day the grain was delivered
     * @param daysAfter how many days after the delivery it came in
     * @param partyCode who it is, when known
     * @param binId the bin the grain came out of, when known
     * @param ticketNumber the scale ticket, when there is one
     * @param saleNumber the sale, when there is one
     * @param probeId the retained sample, when there is one
     * @param outcome how it was decided; {@code open} until it is
     * @param credit what the house gave back
     * @param closedOn the day it closed, once it has
     * @param note what the office wrote on it
     * @param isOpen whether it is still being worked
     * @param isLate whether it came in outside the window the house normally entertains
     * @param sampleIsAvailable whether its retained sample is still on the shelf
     */
    public record ComplaintOut(
            String reference,
            Source source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn,
            int daysAfter,
            Optional<String> partyCode,
            Optional<String> binId,
            Optional<String> ticketNumber,
            Optional<String> saleNumber,
            Optional<String> probeId,
            Outcome outcome,
            BigDecimal credit,
            Optional<LocalDate> closedOn,
            String note,
            boolean isOpen,
            boolean isLate,
            boolean sampleIsAvailable) {

        public ComplaintOut {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * The complaints page the manager looks at once a month.
     *
     * @param opens the first day of the span
     * @param closes the last day of the span
     * @param count every complaint raised in the span
     * @param open how many of them are still open
     * @param late how many came in outside the normal window
     * @param upheldRate of those closed, the percent upheld
     * @param credits what the house gave back on them
     * @param bySource how many came from each kind of caller
     * @param indefensible the open ones with no sample left to argue from
     * @param repeatBins the bins complained about three times or more
     * @param windowDays how many days after a delivery a complaint is normally entertained
     */
    public record SummaryOut(
            LocalDate opens,
            LocalDate closes,
            int count,
            int open,
            int late,
            BigDecimal upheldRate,
            BigDecimal credits,
            Map<String, Integer> bySource,
            List<String> indefensible,
            List<String> repeatBins,
            int windowDays) {

        public SummaryOut {
            Objects.requireNonNull(opens, "opens");
            bySource = Ordered.copyOf(bySource);
            indefensible = List.copyOf(indefensible);
            repeatBins = List.copyOf(repeatBins);
        }
    }
}
