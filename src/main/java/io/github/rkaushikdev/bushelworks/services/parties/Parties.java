package io.github.rkaushikdev.bushelworks.services.parties;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SplitAgreement;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PartyNotFound;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The producer file.
 *
 * <p>A party is whoever the house writes a cheque to or takes grain from. The same row serves as
 * producer, landlord, tenant and lienholder, because the same person is often three of those on
 * different farms.
 *
 * <p>Split agreements hang off a farm rather than off a party, and they are effective dated. The
 * scale house copies the split onto the ticket at the window, so a change to the agreement in
 * December cannot move money on an October load. That copy is the whole point of the arrangement,
 * and the reason the agreement is not read at settlement time.
 *
 * <p>Everything here writes inside the caller's unit of work and nothing commits.
 */
public final class Parties {

    private Parties() {}

    /** Put a new producer on the file, in Kansas, opened today. */
    public static Party openAccount(UnitOfWork uow, String code, String name) {
        return openAccount(uow, Opening.of(code, name));
    }

    /** Put a new producer on the file, in Kansas, opened on the day given. */
    public static Party openAccount(UnitOfWork uow, String code, String name, LocalDate openedOn) {
        return openAccount(uow, Opening.of(code, name).withOpenedOn(openedOn));
    }

    /**
     * Put a new producer on the file. The sort name and the state are stored upper case.
     *
     * @throws PartyError when the account has no code or no name
     * @throws ConflictError when the code is already on the file
     */
    public static Party openAccount(UnitOfWork uow, Opening opening) {
        if (opening.code().isEmpty()) {
            throw new PartyError("a party needs a code");
        }
        if (opening.name().isEmpty()) {
            throw new PartyError(opening.code() + ": a party needs a name");
        }
        boolean existing = !uow.session()
                .createQuery("select p.id from Party p where p.code = :code", Integer.class)
                .setParameter("code", opening.code())
                .getResultList()
                .isEmpty();
        if (existing) {
            throw new ConflictError(opening.code() + " is already on the file");
        }

        Party row = new Party();
        row.setCode(opening.code());
        row.setName(opening.name());
        row.setSortName(opening.sortName()
                .filter(sortName -> !sortName.isEmpty())
                .orElse(opening.name())
                .toUpperCase(Locale.ROOT));
        row.setStateCode(opening.stateCode().toUpperCase(Locale.ROOT));
        row.setTaxId(opening.taxId().orElse(null));
        row.setOpenedOn(opening.openedOn().orElseGet(LocalDate::now));
        uow.session().persist(row);
        uow.flush();
        return row;
    }

    /**
     * Close a producer's account. Nothing is deleted.
     *
     * <p>A closed account keeps its history and stops appearing on the pick list. It is refused while
     * anything is still unsettled, because the office would then have no way to reach the row that
     * owes the money.
     *
     * @throws PartyNotFound when the code is not on the file
     * @throws ConflictError when the account is closed already, or naming up to five of the tickets
     *     still waiting to be settled
     */
    public static Party closeAccount(UnitOfWork uow, String code, LocalDate closedOn) {
        Party row = uow.parties().get(code);
        if (!row.isActive()) {
            throw new ConflictError(code + " is already closed");
        }

        List<ScaleTicketRow> waiting = uow.tickets().forParty(code).stream()
                .filter(ticket -> !ticket.isSettled() && !ticket.isVoided())
                .toList();
        if (!waiting.isEmpty()) {
            String listed = waiting.stream()
                    .limit(5)
                    .map(ScaleTicketRow::getTicketNumber)
                    .collect(Collectors.joining(", "));
            throw new ConflictError(code + " still has " + waiting.size() + " unsettled tickets: " + listed);
        }

        row.setActive(false);
        row.setClosedOn(closedOn);
        uow.flush();
        return row;
    }

    /**
     * Mark a producer as exempt from the checkoff, or take the mark off.
     *
     * @throws PartyNotFound when the code is not on the file
     */
    public static Party setExempt(UnitOfWork uow, String code, boolean exempt) {
        Party row = uow.parties().get(code);
        row.setCheckoffExempt(exempt);
        uow.flush();
        return row;
    }

    /**
     * File a farm's split. It has to add up before it goes on, and it ends whichever agreement was in
     * force on the day it starts.
     *
     * @throws PartyError when there is no farm or no share, the shares do not make 100%, or a party
     *     appears twice
     * @throws PartyNotFound when a share names a party not on the file
     * @throws ConflictError when an agreement in force already runs from that day or later
     */
    public static Agreement fileAgreement(
            UnitOfWork uow, String farmCode, List<SplitShare> shares, LocalDate effectiveFrom) {
        if (farmCode.isEmpty()) {
            throw new PartyError("an agreement needs a farm");
        }
        if (shares.isEmpty()) {
            throw new PartyError(farmCode + ": an agreement needs at least one share");
        }

        Agreement agreement = new Agreement(farmCode, effectiveFrom, shares);
        if (!agreement.isBalanced()) {
            throw new PartyError(farmCode + ": shares total " + agreement.totalPercent() + "%, they have to make 100%");
        }

        Set<String> seen = new HashSet<>();
        for (SplitShare share : shares) {
            if (!seen.add(share.partyId())) {
                throw new PartyError(farmCode + ": " + share.partyId() + " appears twice");
            }
            uow.parties().get(share.partyId());
        }

        List<SplitAgreement> inForce = uow.session()
                .createQuery(
                        "select a from SplitAgreement a where a.farmCode = :farm and a.effectiveTo is null"
                                + " order by a.id",
                        SplitAgreement.class)
                .setParameter("farm", farmCode)
                .getResultList();
        for (SplitAgreement row : inForce) {
            if (!row.getEffectiveFrom().isBefore(effectiveFrom)) {
                throw new ConflictError(farmCode + ": an agreement already runs from " + row.getEffectiveFrom());
            }
            row.setEffectiveTo(effectiveFrom);
        }

        for (SplitShare share : shares) {
            Party party = uow.parties().get(share.partyId());
            SplitAgreement row = new SplitAgreement();
            row.setFarmCode(farmCode);
            row.setPartyId(party.getId());
            row.setPercent(share.percent());
            row.setRole(share.role().value());
            row.setEffectiveFrom(effectiveFrom);
            uow.session().persist(row);
        }
        uow.flush();
        return agreement;
    }

    /**
     * The split in force on a day, for copying onto a ticket, its shares in party code order.
     *
     * @throws NoSplitAgreement when none is in force that day, or the agreement names a party that
     *     has gone from the file
     */
    public static Agreement agreementFor(UnitOfWork uow, String farmCode, LocalDate on) {
        List<SplitAgreement> live = uow
                .session()
                .createQuery(
                        "select a from SplitAgreement a where a.farmCode = :farm and a.effectiveFrom <= :on"
                                + " order by a.id",
                        SplitAgreement.class)
                .setParameter("farm", farmCode)
                .setParameter("on", on)
                .getResultList()
                .stream()
                .filter(row -> row.getEffectiveTo().map(to -> to.isAfter(on)).orElse(true))
                .toList();
        if (live.isEmpty()) {
            throw new NoSplitAgreement("no split on file for " + farmCode + " on " + on);
        }

        Map<Integer, String> codes = new HashMap<>();
        for (SplitAgreement row : live) {
            Party party = uow.session().find(Party.class, row.getPartyId());
            if (party == null) {
                throw new NoSplitAgreement(farmCode + ": the agreement names a party that is no longer on file");
            }
            codes.put(row.getPartyId(), party.getCode());
        }
        List<SplitShare> shares = live.stream()
                .sorted(Comparator.comparing((SplitAgreement row) -> codes.get(row.getPartyId())))
                .map(row -> new SplitShare(codes.get(row.getPartyId()), row.getPercent(), SplitRole.of(row.getRole())))
                .toList();
        LocalDate from = live.stream()
                .map(SplitAgreement::getEffectiveFrom)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new Agreement(farmCode, from, shares);
    }

    /**
     * Every farm a producer has a share of, sorted.
     *
     * @throws PartyNotFound when the code is not on the file
     */
    public static List<String> farmsFor(UnitOfWork uow, String partyCode) {
        Party party = uow.parties().get(partyCode);
        return uow
                .session()
                .createQuery("select distinct a.farmCode from SplitAgreement a where a.partyId = :party", String.class)
                .setParameter("party", party.getId())
                .getResultList()
                .stream()
                .sorted()
                .toList();
    }
}
