package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.persistence.models.AccountRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalLegRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The chart, the journal and the periods.
 *
 * <p>The journal has no update method and no delete method, and that is the point of the class
 * rather than an omission. Everything the office needs to do to a wrong entry is done by posting
 * another one.
 *
 * <p>{@link #chart()} reads the whole account table and builds the domain object, because the chart
 * is twenty five rows and every caller wants the tree rather than a row. The journal is the other way
 * round: it is read a month at a time and returned as rows, and the caller decides whether it wants
 * domain entries out of them.
 */
public final class LedgerRepository {

    private final EntityManager session;

    public LedgerRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    // The chart

    /** The filed chart of active accounts. */
    public ChartOfAccounts chart() {
        return chart(false);
    }

    /**
     * The filed chart, parents placed before their children so it builds however the rows were
     * filed.
     *
     * @throws ConflictError naming every account whose parent is not on file
     */
    public ChartOfAccounts chart(boolean includeInactive) {
        String filter = includeInactive ? "" : " where a.active = true";
        List<AccountRow> remaining = session.createQuery(
                        "select a from AccountRow a" + filter + " order by a.code", AccountRow.class)
                .getResultList();
        ChartOfAccounts chart = new ChartOfAccounts();
        Set<String> placed = new HashSet<>();
        while (!remaining.isEmpty()) {
            List<AccountRow> still = new ArrayList<>();
            for (AccountRow row : remaining) {
                if (row.getParentCode().map(placed::contains).orElse(true)) {
                    chart.add(accountFrom(row));
                    placed.add(row.getCode());
                } else {
                    still.add(row);
                }
            }
            if (still.size() == remaining.size()) {
                String orphans =
                        still.stream().map(AccountRow::getCode).sorted().collect(Collectors.joining(", "));
                throw new ConflictError("these accounts name a parent that is not on file: " + orphans);
            }
            remaining = still;
        }
        return chart;
    }

    /**
     * File a chart where none is on file yet, and flush it.
     *
     * @throws ConflictError when any account is on file already
     */
    public void fileChart(ChartOfAccounts chart) {
        Long existing = session.createQuery("select count(a) from AccountRow a", Long.class)
                .getSingleResult();
        if (existing > 0) {
            throw new ConflictError(
                    "the chart already has " + existing + " accounts on file; add to it rather than filing it again");
        }
        for (Account account : chart) {
            session.persist(rowFor(account));
        }
        session.flush();
    }

    /**
     * Add one account to a filed chart, and flush it.
     *
     * @throws ConflictError when its code is already on file
     */
    public AccountRow addAccount(Account account) {
        boolean clash = Queries.first(
                        session.createQuery("select a from AccountRow a where a.code = :code", AccountRow.class)
                                .setParameter("code", account.code()))
                .isPresent();
        if (clash) {
            throw new ConflictError("account " + account.code() + " is already on file");
        }
        AccountRow row = rowFor(account);
        session.persist(row);
        session.flush();
        return row;
    }

    // The journal

    /** Post a balanced entry that reverses nothing. */
    public JournalEntryRow post(JournalEntry entry) {
        return post(entry, null);
    }

    /**
     * Write a balanced entry, its legs numbered from one, and flush it. The caller checks it against
     * the chart first.
     *
     * @param reverses the number of the entry this one reverses, or null
     * @throws io.github.rkaushikdev.bushelworks.domain.ledger.LedgerError when it does not balance
     * @throws ConflictError when its number is already posted
     */
    public JournalEntryRow post(JournalEntry entry, String reverses) {
        entry.checkBalances();
        if (find(entry.number()).isPresent()) {
            throw new ConflictError("journal entry " + entry.number() + " is already posted");
        }
        JournalEntryRow row = new JournalEntryRow();
        row.setNumber(entry.number());
        row.setPostedOn(entry.postedOn());
        row.setMemo(entry.memo());
        row.setSource(entry.source());
        row.setSourceReference(entry.sourceReference());
        row.setReverses(reverses);
        int sequence = 1;
        for (Leg leg : entry.legs()) {
            JournalLegRow stored = new JournalLegRow();
            stored.setSequence(sequence++);
            stored.setAccountCode(leg.accountCode());
            stored.setSide(leg.side().value());
            stored.setDollars(leg.dollars());
            stored.setMemo(leg.memo());
            row.addLeg(stored);
        }
        session.persist(row);
        session.flush();
        return row;
    }

    /**
     * The stored row for one entry.
     *
     * @throws JournalEntryNotFound when there is none with that number
     */
    public JournalEntryRow row(String number) {
        return find(number).orElseThrow(() -> new JournalEntryNotFound("no journal entry " + number));
    }

    /**
     * One entry, read back as the domain object.
     *
     * @throws JournalEntryNotFound when there is none with that number
     */
    public JournalEntry entry(String number) {
        return entryFrom(row(number));
    }

    /** The entries posted in a span, both days included, by day and then number. */
    public List<JournalEntryRow> between(LocalDate opens, LocalDate closes) {
        return session.createQuery(
                        "select e from JournalEntryRow e where e.postedOn >= :opens and e.postedOn <= :closes"
                                + " order by e.postedOn, e.number",
                        JournalEntryRow.class)
                .setParameter("opens", opens)
                .setParameter("closes", closes)
                .getResultList();
    }

    /** The same entries as {@link #between}, read back as domain objects. */
    public List<JournalEntry> entriesBetween(LocalDate opens, LocalDate closes) {
        return between(opens, closes).stream().map(LedgerRepository::entryFrom).toList();
    }

    /** Every entry raised out of one document, the reversal included, by day and then number. */
    public List<JournalEntryRow> forSource(String source, String reference) {
        return session.createQuery(
                        "select e from JournalEntryRow e where e.source = :source and e.sourceReference = :reference"
                                + " order by e.postedOn, e.number",
                        JournalEntryRow.class)
                .setParameter("source", source)
                .setParameter("reference", reference)
                .getResultList();
    }

    /** Whether anything has been posted out of the document. */
    public boolean isPosted(String source, String reference) {
        return !forSource(source, reference).isEmpty();
    }

    /** The entry that reverses entry {@code number}, if one has been posted. */
    public Optional<JournalEntryRow> reversalOf(String number) {
        return Queries.first(
                session.createQuery("select e from JournalEntryRow e where e.reverses = :number", JournalEntryRow.class)
                        .setParameter("number", number));
    }

    /** The legs posted to one account in a span, both days included, by day and then leg order. */
    public List<JournalLegRow> legsOn(String accountCode, LocalDate opens, LocalDate closes) {
        return session.createQuery(
                        "select l from JournalLegRow l join l.entry e where l.accountCode = :account"
                                + " and e.postedOn >= :opens and e.postedOn <= :closes"
                                + " order by e.postedOn, l.sequence, l.id",
                        JournalLegRow.class)
                .setParameter("account", accountCode)
                .setParameter("opens", opens)
                .setParameter("closes", closes)
                .getResultList();
    }

    /**
     * The next journal number in the house's own shape, {@code J-202505-0008}, for tests and back
     * fills.
     *
     * <p>Real numbering comes off the locked counter row in the numbering service. This is here so a
     * repository test does not have to stand a counter up to post two entries.
     */
    public String nextNumber(String prefix, LocalDate on) {
        String stem = String.format(Locale.ROOT, "%s-%04d%02d-", prefix, on.getYear(), on.getMonthValue());
        int highest = 0;
        for (String number : session.createQuery(
                        "select e.number from JournalEntryRow e where e.number like :stem", String.class)
                .setParameter("stem", stem + "%")
                .getResultList()) {
            String tail = number.substring(number.lastIndexOf('-') + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(letter -> letter >= '0' && letter <= '9')) {
                highest = Math.max(highest, Integer.parseInt(tail));
            }
        }
        return String.format(Locale.ROOT, "%s%04d", stem, highest + 1);
    }

    // Periods

    /**
     * The period filed under {@code code}.
     *
     * @throws PeriodNotFound when there is none
     */
    public PeriodRow period(String code) {
        return Queries.first(session.createQuery("select p from PeriodRow p where p.code = :code", PeriodRow.class)
                        .setParameter("code", code))
                .orElseThrow(() -> new PeriodNotFound("no accounting period " + code));
    }

    /**
     * The period {@code day} falls in.
     *
     * @throws PeriodNotFound when no period covers it
     */
    public PeriodRow periodCovering(LocalDate day) {
        return Queries.first(session.createQuery(
                                "select p from PeriodRow p where p.startsOn <= :day and p.endsOn >= :day",
                                PeriodRow.class)
                        .setParameter("day", day))
                .orElseThrow(() -> new PeriodNotFound("no accounting period covers " + day));
    }

    /** The periods still open to posting, earliest first. */
    public List<PeriodRow> openPeriods() {
        return session.createQuery(
                        "select p from PeriodRow p where p.closed = false order by p.startsOn", PeriodRow.class)
                .getResultList();
    }

    /**
     * File periods, and flush them.
     *
     * @throws ConflictError when a period's code is already on file
     */
    public List<PeriodRow> addPeriods(Iterable<PeriodRow> periods) {
        List<PeriodRow> added = new ArrayList<>();
        for (PeriodRow row : periods) {
            boolean clash = Queries.first(
                            session.createQuery("select p from PeriodRow p where p.code = :code", PeriodRow.class)
                                    .setParameter("code", row.getCode()))
                    .isPresent();
            if (clash) {
                throw new ConflictError("period " + row.getCode() + " is already on file");
            }
            session.persist(row);
            added.add(row);
        }
        session.flush();
        return List.copyOf(added);
    }

    private Optional<JournalEntryRow> find(String number) {
        return Queries.first(
                session.createQuery("select e from JournalEntryRow e where e.number = :number", JournalEntryRow.class)
                        .setParameter("number", number));
    }

    private static AccountRow rowFor(Account account) {
        AccountRow row = new AccountRow();
        row.setCode(account.code());
        row.setName(account.name());
        row.setType(account.type().value());
        row.setParentCode(account.parent().orElse(null));
        row.setClosesTo(account.closesTo().orElse(null));
        return row;
    }

    private static Account accountFrom(AccountRow row) {
        return new Account(
                row.getCode(), row.getName(), AccountType.of(row.getType()), row.getParentCode(), row.getClosesTo());
    }

    private static JournalEntry entryFrom(JournalEntryRow row) {
        List<Leg> legs = row.getLegs().stream()
                .map(leg -> new Leg(leg.getAccountCode(), Side.of(leg.getSide()), leg.getDollars(), leg.getMemo()))
                .toList();
        return new JournalEntry(
                row.getNumber(), row.getPostedOn(), row.getMemo(), legs, row.getSource(), row.getSourceReference());
    }
}
