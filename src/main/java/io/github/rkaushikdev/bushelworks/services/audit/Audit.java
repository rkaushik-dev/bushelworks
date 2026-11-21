package io.github.rkaushikdev.bushelworks.services.audit;

import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.AuditRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Writing and reading the audit trail.
 *
 * <p>The trail is append only and this is the only way in. There is no update and no delete, and
 * the read side is deliberately narrow: by document, by producer, by day. Anything wider than that
 * is a report, and a report should not be able to reach the evidence table directly.
 */
public final class Audit {

    /** The actor recorded when nobody in particular did it. */
    public static final String SYSTEM = "system";

    /** The longest detail kept; a longer one is trimmed rather than refused. */
    public static final int DETAIL_LENGTH = 400;

    /** How many entries {@link #forParty(UnitOfWork, String)} hands back. */
    public static final int PARTY_LIMIT = 200;

    private Audit() {}

    /** Write one entry done by the system, with nothing else said. */
    public static AuditRow record(UnitOfWork uow, String action, String documentKind, String documentNumber) {
        return record(uow, action, documentKind, documentNumber, Particulars.none());
    }

    /**
     * Write one entry inside the caller's transaction.
     *
     * <p>Inside the caller's transaction on purpose. An audit row that survives a rolled back
     * settlement is worse than no audit row, because it says something happened that did not. The
     * request being served, if there is one, is recorded with it.
     *
     * @throws AuditError when there is no action or no document number
     */
    public static AuditRow record(
            UnitOfWork uow, String action, String documentKind, String documentNumber, Particulars particulars) {
        if (action.isEmpty()) {
            throw new AuditError("an audit entry needs an action");
        }
        if (documentNumber.isEmpty()) {
            throw new AuditError(action + ": an audit entry needs a document number");
        }
        AuditRow row = new AuditRow();
        row.setAction(action);
        row.setDocumentKind(documentKind);
        row.setDocumentNumber(documentNumber);
        row.setActor(particulars.actor().isEmpty() ? SYSTEM : particulars.actor());
        row.setPartyCode(particulars.partyCode().orElse(null));
        row.setLocationCode(particulars.locationCode().orElse(null));
        row.setAmount(particulars.amount().orElse(null));
        row.setDetail(particulars
                .detail()
                .filter(detail -> !detail.isEmpty())
                .map(detail -> detail.length() > DETAIL_LENGTH ? detail.substring(0, DETAIL_LENGTH) : detail)
                .orElse(null));
        row.setEffectiveOn(particulars.effectiveOn().orElse(null));
        row.setRequestId(HouseLog.currentRequestId().orElse(null));
        uow.session().persist(row);
        uow.flush();
        return row;
    }

    /** Everything that ever happened to one document, oldest first. */
    public static List<Entry> forDocument(UnitOfWork uow, String documentKind, String documentNumber) {
        return uow
                .session()
                .createQuery(
                        "select a from AuditRow a where a.documentKind = :kind and a.documentNumber = :number"
                                + " order by a.recordedAt, a.id",
                        AuditRow.class)
                .setParameter("kind", documentKind)
                .setParameter("number", documentNumber)
                .getResultList()
                .stream()
                .map(Audit::toEntry)
                .toList();
    }

    /** The latest {@value #PARTY_LIMIT} entries about one producer, newest first. */
    public static List<Entry> forParty(UnitOfWork uow, String partyCode) {
        return forParty(uow, partyCode, PARTY_LIMIT);
    }

    /** The latest {@code limit} entries about one producer, newest first. */
    public static List<Entry> forParty(UnitOfWork uow, String partyCode, int limit) {
        return uow
                .session()
                .createQuery(
                        "select a from AuditRow a where a.partyCode = :party order by a.recordedAt desc, a.id desc",
                        AuditRow.class)
                .setParameter("party", partyCode)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(Audit::toEntry)
                .toList();
    }

    /** Everything recorded on one day, in UTC, oldest first. */
    public static List<Entry> forDay(UnitOfWork uow, LocalDate day) {
        return forDay(uow, day, null);
    }

    /**
     * What was recorded on one day, in UTC, oldest first.
     *
     * @param action only entries doing this; null or empty for every entry
     */
    public static List<Entry> forDay(UnitOfWork uow, LocalDate day, String action) {
        boolean filtered = action != null && !action.isEmpty();
        var query = uow.session()
                .createQuery(
                        "select a from AuditRow a where a.recordedAt >= :opens and a.recordedAt < :closes"
                                + (filtered ? " and a.action = :action" : "")
                                + " order by a.recordedAt, a.id",
                        AuditRow.class)
                .setParameter("opens", day.atStartOfDay().atOffset(ZoneOffset.UTC))
                .setParameter("closes", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        if (filtered) {
            query.setParameter("action", action);
        }
        return query.getResultList().stream().map(Audit::toEntry).toList();
    }

    /** How many entries the trail holds. */
    public static long count(UnitOfWork uow) {
        return uow.session()
                .createQuery("select count(a) from AuditRow a", Long.class)
                .getSingleResult();
    }

    /** What most recently happened to a document, or nothing if it is new. */
    public static Optional<Entry> lastAction(UnitOfWork uow, String documentKind, String documentNumber) {
        List<Entry> entries = forDocument(uow, documentKind, documentNumber);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }

    private static Entry toEntry(AuditRow row) {
        OffsetDateTime recordedAt = row.getRecordedAt().withOffsetSameInstant(ZoneOffset.UTC);
        return new Entry(
                recordedAt,
                row.getActor(),
                row.getAction(),
                row.getDocumentKind(),
                row.getDocumentNumber(),
                row.getPartyCode(),
                row.getAmount(),
                row.getDetail());
    }
}
