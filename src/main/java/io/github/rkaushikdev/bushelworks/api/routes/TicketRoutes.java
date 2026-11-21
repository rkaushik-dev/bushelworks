package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Paging;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.schemas.GradeOut;
import io.github.rkaushikdev.bushelworks.api.schemas.GradedLoadOut;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import io.github.rkaushikdev.bushelworks.api.schemas.SampleReading;
import io.github.rkaushikdev.bushelworks.api.schemas.SplitIn;
import io.github.rkaushikdev.bushelworks.api.schemas.TicketIn;
import io.github.rkaushikdev.bushelworks.api.schemas.TicketOut;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import io.github.rkaushikdev.bushelworks.services.scalehouse.RecordedLoad;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
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
 * Scale ticket routes.
 *
 * <p>There is no PUT and no DELETE here on purpose. A ticket is written once and corrected by
 * voiding, which is a POST to its own sub resource rather than a change to the ticket, because the
 * void is a document in its own right.
 *
 * <p>The driveway terminal holds a scale key and needs to both write and read tickets, so every route
 * here sits at that level rather than splitting the reads down to a read key nobody at the scale
 * house has.
 */
@RestController
@RequestMapping("/tickets")
@Requires(Role.SCALE)
@Tag(name = "tickets")
public class TicketRoutes {

    private final Work work;
    private final RuleBook rules;
    private final Settings settings;

    public TicketRoutes(Work work, RuleBook rules, Settings settings) {
        this.work = work;
        this.rules = rules;
        this.settings = settings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a load across the scale")
    public GradedLoadOut createTicket(@RequestBody TicketIn body, HttpServletResponse response) {
        return work.apply(uow -> {
            RecordedLoad recorded = ScaleHouse.recordLoad(uow, toDomain(body), body.partyCode(), rules);
            uow.commit();
            response.setHeader(HttpHeaders.LOCATION, "/tickets/" + body.locationCode() + "/" + body.ticketNumber());
            return loadOut(recorded.load());
        });
    }

    /**
     * Record several tickets, reporting each one on its own.
     *
     * <p>A refused ticket does not cost the rest of the batch. The terminal holds tickets when the
     * line is down and sends nine at once when it comes back; one duplicate in the middle used to
     * lose the other eight, and the driver of the eighth had already gone home. Each ticket is
     * committed as it is taken, so a later refusal cannot roll an earlier one back.
     */
    @PostMapping("/batch")
    @Operation(summary = "Take a run of tickets the terminal held while the line was down")
    @ResponseStatus(HttpStatus.MULTI_STATUS)
    public BatchOut createBatch(@RequestBody BatchIn body) {
        return work.apply(uow -> {
            List<BatchResultOut> results = new ArrayList<>();
            int accepted = 0;
            for (TicketIn ticket : body.tickets()) {
                try {
                    RecordedLoad recorded = ScaleHouse.recordLoad(uow, toDomain(ticket), ticket.partyCode(), rules);
                    uow.commit();
                    accepted++;
                    results.add(BatchResultOut.accepted(
                            ticket.ticketNumber(), recorded.load().dryBushels()));
                } catch (BushelworksError refusal) {
                    uow.rollback();
                    results.add(BatchResultOut.refused(ticket.ticketNumber(), refusal));
                    if (body.stopOnFirstRefusal()) {
                        break;
                    }
                }
            }
            return new BatchOut(accepted, results.size() - accepted, results);
        });
    }

    @GetMapping("/{location_code}/{ticket_number}")
    @Operation(summary = "Read Ticket")
    public TicketOut readTicket(
            @PathVariable("location_code") String locationCode, @PathVariable("ticket_number") String ticketNumber) {
        return work.apply(uow -> ticketOut(uow.tickets().row(locationCode, ticketNumber)));
    }

    /**
     * Regrade a stored ticket against the tables in force on its scale date.
     *
     * <p>Recomputed rather than stored. A stored grade is a copy that goes stale the moment a schedule
     * is corrected, and a ticket is regraded often enough at the window that the recomputation is
     * worth more than the cache.
     */
    @GetMapping("/{location_code}/{ticket_number}/grade")
    @Operation(summary = "Read Grade")
    public GradedLoadOut readGrade(
            @PathVariable("location_code") String locationCode, @PathVariable("ticket_number") String ticketNumber) {
        return work.apply(uow -> {
            ScaleTicketRow row = uow.tickets().row(locationCode, ticketNumber);
            return loadOut(Intake.receive(Mappers.ticketFromRow(row), rules));
        });
    }

    @PostMapping("/{location_code}/{ticket_number}/void")
    @Operation(summary = "Void a ticket, keeping it on file")
    public TicketOut voidTicket(
            @PathVariable("location_code") String locationCode,
            @PathVariable("ticket_number") String ticketNumber,
            @RequestParam("reason")
                    @Size(min = 1, message = "String should have at least 1 character")
                    @Size(max = 200, message = "String should have at most 200 characters")
                    String reason) {
        return work.apply(uow -> {
            ScaleTicketRow row = uow.tickets().voidTicket(locationCode, ticketNumber, reason);
            uow.commit();
            return ticketOut(row);
        });
    }

    @GetMapping
    @Operation(summary = "List Tickets")
    public Page<TicketOut> listTickets(
            @RequestParam(name = "party_code", required = false) String partyCode,
            @RequestParam(name = "location_code", required = false) String locationCode,
            @RequestParam(name = "since", required = false) LocalDate since,
            @RequestParam(name = "unsettled_only", defaultValue = "false") boolean unsettledOnly,
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow -> {
            List<ScaleTicketRow> rows;
            if (unsettledOnly || !given(partyCode)) {
                rows = uow.tickets().awaitingSettlement(locationCode);
            } else {
                rows = uow.tickets().forParty(partyCode, since);
            }
            if (given(locationCode) && given(partyCode)) {
                rows = rows.stream()
                        .filter(row -> row.getLocationCode().equals(locationCode))
                        .toList();
            }
            return page.page(rows, TicketRoutes::ticketOut);
        });
    }

    private static boolean given(String text) {
        return text != null && !text.isEmpty();
    }

    private static ScaleTicket toDomain(TicketIn body) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        body.sample().forEach(reading -> sample.put(reading.factor(), reading.value()));
        return ScaleTicket.builder()
                .ticketNumber(body.ticketNumber())
                .scaleDate(body.scaleDate())
                .locationCode(body.locationCode())
                .commodityCode(body.commodityCode())
                .grossPounds(body.grossPounds())
                .tarePounds(body.tarePounds())
                .disposition(body.disposition())
                .splits(body.splits().stream()
                        .map(split -> new SplitShare(split.partyCode(), split.percent(), split.role()))
                        .toList())
                .sample(sample)
                .dockagePercent(body.dockagePercent())
                .truckId(body.truckId())
                .driver(body.driver())
                .farm(body.farm())
                .fieldName(body.fieldName())
                .contractNumber(body.contractNumber().orElse(null))
                .remarks(body.remarks())
                .build();
    }

    static TicketOut ticketOut(ScaleTicketRow row) {
        List<SampleReading> sample = Mappers.sampleFromRows(row.getSamples()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Factor.BY_VALUE))
                .map(reading -> new SampleReading(reading.getKey(), reading.getValue()))
                .toList();
        List<SplitIn> splits = row.getSplits().stream()
                .map(split -> new SplitIn(split.getPartyCode(), split.getPercent(), SplitRole.of(split.getRole())))
                .toList();
        return new TicketOut(
                row.getTicketNumber(),
                row.getLocationCode(),
                row.getScaleDate(),
                row.getCommodityCode(),
                row.getPartyCode(),
                row.getGrossPounds(),
                row.getTarePounds(),
                row.getNetPounds(),
                row.getDockagePercent(),
                row.getDisposition(),
                row.getContractNumber(),
                row.isVoided(),
                row.isSettled(),
                sample,
                splits);
    }

    static GradedLoadOut loadOut(GradedLoad load) {
        return new GradedLoadOut(
                load.ticketNumber(),
                load.commodityCode(),
                load.grossBushels(),
                load.cleanBushels(),
                load.dryBushels(),
                load.shrinkBushels(),
                load.shrinkPercent(),
                load.moisturePoints(),
                load.discountRate(),
                new GradeOut(
                        load.grade().grade().value(),
                        load.grade().limitingFactors().stream()
                                .map(Factor::value)
                                .toList()));
    }

    /**
     * A run of tickets the driveway terminal held while the line was down.
     *
     * @param tickets one to 200 tickets, each as {@link TicketIn} takes it
     * @param stopOnFirstRefusal whether to stop at the first ticket refused rather than go on
     */
    public record BatchIn(List<TicketIn> tickets, boolean stopOnFirstRefusal) {

        public BatchIn {
            tickets = List.copyOf(tickets);
        }

        /** A batch as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static BatchIn from(JsonNode json) {
            return Body.read(json, BatchIn::read);
        }

        static BatchIn read(Body in) {
            List<TicketIn> tickets = in.list("tickets", TicketIn::read, 1, 200);
            boolean stopOnFirstRefusal = in.flag("stop_on_first_refusal", false);
            return in.build(() -> new BatchIn(tickets, stopOnFirstRefusal));
        }
    }

    /**
     * What became of one ticket of a batch.
     *
     * @param ticketNumber the ticket
     * @param accepted whether it was taken
     * @param code why it was refused, when it was
     * @param detail the refusal's sentence, when it was refused
     * @param dryBushels the bushels it came to, when it was taken
     */
    public record BatchResultOut(
            String ticketNumber,
            boolean accepted,
            Optional<String> code,
            Optional<String> detail,
            Optional<BigDecimal> dryBushels) {

        public BatchResultOut {
            Objects.requireNonNull(ticketNumber, "ticketNumber");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(dryBushels, "dryBushels");
        }

        static BatchResultOut accepted(String ticketNumber, BigDecimal dryBushels) {
            return new BatchResultOut(ticketNumber, true, Optional.empty(), Optional.empty(), Optional.of(dryBushels));
        }

        static BatchResultOut refused(String ticketNumber, BushelworksError refusal) {
            return new BatchResultOut(
                    ticketNumber,
                    false,
                    Optional.of(refusal.code()),
                    Optional.of(refusal.getMessage()),
                    Optional.empty());
        }
    }

    /**
     * A batch, ticket by ticket.
     *
     * @param accepted how many tickets were taken
     * @param refused how many were refused
     * @param results each ticket's result, in the order they were sent, as far as the batch got
     */
    public record BatchOut(int accepted, int refused, List<BatchResultOut> results) {

        public BatchOut {
            results = List.copyOf(results);
        }

        /** Whether every ticket was taken. */
        @JsonIgnore
        public boolean isClean() {
            return refused == 0;
        }
    }
}
