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
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import io.github.rkaushikdev.bushelworks.services.parties.Agreement;
import io.github.rkaushikdev.bushelworks.services.parties.Opening;
import io.github.rkaushikdev.bushelworks.services.parties.Parties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Producer file routes. The producer file is the office's, so every route needs an office key.
 *
 * <p>Closing an account is a POST to a sub resource rather than a DELETE, because nothing is deleted
 * and the closure has a date on it. Filing a split agreement is a POST to the farm, not to the party,
 * because the agreement belongs to the ground.
 */
@RestController
@RequestMapping("/parties")
@Requires(Role.OFFICE)
@Tag(name = "parties")
public class PartyRoutes {

    private final Work work;
    private final Settings settings;

    public PartyRoutes(Work work, Settings settings) {
        this.work = work;
        this.settings = settings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create Party")
    public PartyOut createParty(@RequestBody PartyIn body) {
        return work.apply(uow -> {
            Party row = Parties.openAccount(
                    uow,
                    Opening.of(body.code(), body.name())
                            .withStateCode(body.stateCode())
                            .withSortName(body.sortName().orElse(null))
                            .withTaxId(body.taxId().orElse(null))
                            .withOpenedOn(body.openedOn().orElse(null)));
            PartyOut out = partyOut(row);
            uow.commit();
            return out;
        });
    }

    @GetMapping("/{code}")
    @Operation(summary = "Read Party")
    public PartyOut readParty(@PathVariable("code") String code) {
        return work.apply(uow -> partyOut(uow.parties().get(code)));
    }

    @GetMapping
    @Operation(summary = "List Parties")
    public Page<PartyOut> listParties(
            @RequestParam(name = "offset", defaultValue = Paging.DEFAULT_OFFSET) int offset,
            @RequestParam(name = "limit", defaultValue = Paging.DEFAULT_LIMIT) int limit) {
        Paging page = Paging.clamp(offset, limit, settings);
        return work.apply(uow -> page.page(uow.parties().active(), PartyRoutes::partyOut));
    }

    @PostMapping("/{code}/closure")
    @Operation(summary = "Close")
    public PartyOut close(@PathVariable("code") String code, @RequestParam("on") LocalDate on) {
        return work.apply(uow -> {
            PartyOut out = partyOut(Parties.closeAccount(uow, code, on));
            uow.commit();
            return out;
        });
    }

    @PostMapping("/{code}/checkoff-exemption")
    @Operation(summary = "Exemption")
    public PartyOut exemption(
            @PathVariable("code") String code, @RequestParam(name = "exempt", defaultValue = "true") boolean exempt) {
        return work.apply(uow -> {
            PartyOut out = partyOut(Parties.setExempt(uow, code, exempt));
            uow.commit();
            return out;
        });
    }

    @GetMapping("/{code}/farms")
    @Operation(summary = "Read Farms")
    public FarmsOut readFarms(@PathVariable("code") String code) {
        return work.apply(uow -> new FarmsOut(code, Parties.farmsFor(uow, code)));
    }

    @PostMapping("/farms/{farm_code}/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create Agreement")
    public AgreementOut createAgreement(@PathVariable("farm_code") String farmCode, @RequestBody AgreementIn body) {
        return work.apply(uow -> {
            List<SplitShare> shares = body.shares().stream()
                    .map(share -> new SplitShare(share.partyCode(), share.percent(), share.role()))
                    .toList();
            Agreement agreement = Parties.fileAgreement(uow, farmCode, shares, body.effectiveFrom());
            uow.commit();
            return agreementOut(agreement);
        });
    }

    @GetMapping("/farms/{farm_code}/agreement")
    @Operation(
            summary = "Read Agreement",
            description = "The split in force on a day, which is what the window copies.")
    public AgreementOut readAgreement(@PathVariable("farm_code") String farmCode, @RequestParam("on") LocalDate on) {
        return work.apply(uow -> agreementOut(Parties.agreementFor(uow, farmCode, on)));
    }

    private static PartyOut partyOut(Party row) {
        return new PartyOut(
                row.getCode(),
                row.getName(),
                row.getSortName(),
                row.getStateCode(),
                row.getTaxId(),
                row.isActive(),
                row.isCheckoffExempt(),
                row.getOpenedOn(),
                row.getClosedOn());
    }

    private static AgreementOut agreementOut(Agreement agreement) {
        return new AgreementOut(
                agreement.farmCode(),
                agreement.effectiveFrom(),
                agreement.shares().stream()
                        .map(share -> new ShareIn(share.partyId(), share.percent(), share.role()))
                        .toList(),
                agreement.totalPercent(),
                agreement.isBalanced());
    }

    /**
     * A new account, as the office keys it.
     *
     * @param code what the office calls the producer, one to 24 characters
     * @param name the name cheques are written to, one to 120 characters
     * @param sortName the name the pick list sorts by; the name itself when there is none
     * @param stateCode the state the producer is in, Kansas unless the office says otherwise
     * @param taxId the number the year end return is filed under
     * @param openedOn the day the account opened; today when there is none
     */
    public record PartyIn(
            String code,
            String name,
            Optional<String> sortName,
            String stateCode,
            Optional<String> taxId,
            Optional<LocalDate> openedOn) {

        public PartyIn {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sortName, "sortName");
            Objects.requireNonNull(stateCode, "stateCode");
            Objects.requireNonNull(taxId, "taxId");
            Objects.requireNonNull(openedOn, "openedOn");
        }

        /** An account as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PartyIn from(JsonNode json) {
            return Body.read(json, PartyIn::read);
        }

        static PartyIn read(Body in) {
            String code = in.text("code", 1, 24);
            String name = in.text("name", 1, 120);
            Optional<String> sortName = in.optionalText("sort_name");
            String stateCode = in.text("state_code", "KS");
            Optional<String> taxId = in.optionalText("tax_id");
            Optional<LocalDate> openedOn = in.optionalDate("opened_on");
            return in.build(() -> new PartyIn(code, name, sortName, stateCode, taxId, openedOn));
        }
    }

    /**
     * One party's share of every load off a farm.
     *
     * @param partyCode the party, one to 24 characters
     * @param percent the share
     * @param role why the party has it; an owner's share when the caller does not say
     */
    public record ShareIn(String partyCode, BigDecimal percent, SplitRole role) {

        public ShareIn {
            Objects.requireNonNull(partyCode, "partyCode");
            Objects.requireNonNull(percent, "percent");
            Objects.requireNonNull(role, "role");
        }

        static ShareIn read(Body in) {
            String partyCode = in.text("party_code", 1, 24);
            BigDecimal percent = in.quantity("percent");
            SplitRole role = in.choice("role", SplitRole.class, SplitRole.OWNER);
            return in.build(() -> new ShareIn(partyCode, percent, role));
        }
    }

    /**
     * A farm's split, from a day on.
     *
     * @param effectiveFrom the first day it applies
     * @param shares at least one share
     */
    public record AgreementIn(LocalDate effectiveFrom, List<ShareIn> shares) {

        public AgreementIn {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            shares = List.copyOf(shares);
        }

        /** An agreement as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static AgreementIn from(JsonNode json) {
            return Body.read(json, AgreementIn::read);
        }

        static AgreementIn read(Body in) {
            LocalDate effectiveFrom = in.date("effective_from");
            List<ShareIn> shares = in.list("shares", ShareIn::read, 1, Integer.MAX_VALUE);
            return in.build(() -> new AgreementIn(effectiveFrom, shares));
        }
    }

    /**
     * A producer's account, as it stands.
     *
     * @param code what the office calls the producer
     * @param name the name cheques are written to
     * @param sortName the name the pick list sorts by
     * @param stateCode the state the producer is in
     * @param taxId the number the year end return is filed under, when there is one
     * @param isActive whether the account is open
     * @param checkoffExempt whether the producer is exempt from the checkoff
     * @param openedOn the day the account opened
     * @param closedOn the day the account closed, when it has
     */
    public record PartyOut(
            String code,
            String name,
            String sortName,
            String stateCode,
            Optional<String> taxId,
            boolean isActive,
            boolean checkoffExempt,
            Optional<LocalDate> openedOn,
            Optional<LocalDate> closedOn) {

        public PartyOut {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(taxId, "taxId");
            Objects.requireNonNull(openedOn, "openedOn");
            Objects.requireNonNull(closedOn, "closedOn");
        }
    }

    /**
     * A farm's split, as it stands.
     *
     * @param farmCode the farm
     * @param effectiveFrom the first day it applies
     * @param shares who takes what part
     * @param totalPercent the shares added up
     * @param isBalanced whether they make exactly the whole load
     */
    public record AgreementOut(
            String farmCode,
            LocalDate effectiveFrom,
            List<ShareIn> shares,
            BigDecimal totalPercent,
            boolean isBalanced) {

        public AgreementOut {
            Objects.requireNonNull(farmCode, "farmCode");
            shares = List.copyOf(shares);
        }
    }

    /**
     * The farms a producer has a share in.
     *
     * @param partyCode the producer
     * @param farms the farms, in code order
     */
    public record FarmsOut(String partyCode, List<String> farms) {

        public FarmsOut {
            Objects.requireNonNull(partyCode, "partyCode");
            farms = List.copyOf(farms);
        }
    }
}
