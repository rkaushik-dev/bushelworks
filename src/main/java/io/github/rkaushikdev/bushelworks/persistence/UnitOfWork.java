package io.github.rkaushikdev.bushelworks.persistence;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.repositories.ComplaintRepository;
import io.github.rkaushikdev.bushelworks.persistence.repositories.ContractRepository;
import io.github.rkaushikdev.bushelworks.persistence.repositories.LedgerRepository;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PartyRepository;
import io.github.rkaushikdev.bushelworks.persistence.repositories.SettlementRepository;
import io.github.rkaushikdev.bushelworks.persistence.repositories.TicketRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/**
 * One transaction, with every repository bound to it.
 *
 * <p>Taking a load in touches four tables and settling one touches five. Either all of it lands or
 * none of it does, and the caller should not have to remember which session each repository was
 * built against: they all share this one, so what one writes the next sees without a commit.
 *
 * <p>Closing without committing rolls back. That is deliberate: the failure mode that matters is a
 * half written settlement, and defaulting to rollback makes forgetting safe instead of expensive. A
 * commit begins the next transaction straight away, so work can carry on in the same unit.
 *
 * <pre>{@code
 * try (UnitOfWork uow = UnitOfWork.open(database)) {
 *     ScaleTicketRow row = uow.tickets().add(ticket, "MERRICK");
 *     uow.settlements().add(statement, List.of(row.getId()));
 *     uow.commit();
 * }
 * }</pre>
 *
 * <p>Reaching for the session or a repository while the unit is not open is refused with a sentence
 * saying so, rather than failing three frames further down.
 */
public final class UnitOfWork implements AutoCloseable {

    private final Database database;
    private EntityManager session;
    private TicketRepository tickets;
    private ContractRepository contracts;
    private SettlementRepository settlements;
    private PartyRepository parties;
    private ComplaintRepository complaints;
    private LedgerRepository ledger;
    private boolean committed;

    /** A unit of work over {@code database}, not open yet; {@link #begin()} opens it. */
    public UnitOfWork(Database database) {
        this.database = database;
    }

    /** A unit of work over {@code database}, open, its transaction begun. */
    public static UnitOfWork open(Database database) {
        return new UnitOfWork(database).begin();
    }

    /**
     * Open a session, begin its transaction and bind the repositories to it.
     *
     * @return this unit, for try-with-resources
     * @throws ConflictError when it is open already
     */
    public UnitOfWork begin() {
        if (session != null) {
            throw new ConflictError("this unit of work is already open");
        }
        EntityManager opened = database.openSession();
        try {
            opened.getTransaction().begin();
        } catch (RuntimeException trouble) {
            opened.close();
            throw trouble;
        }
        session = opened;
        tickets = new TicketRepository(opened);
        contracts = new ContractRepository(opened);
        settlements = new SettlementRepository(opened);
        parties = new PartyRepository(opened);
        complaints = new ComplaintRepository(opened);
        ledger = new LedgerRepository(opened);
        committed = false;
        return this;
    }

    /** The session every repository of this unit shares. */
    public EntityManager session() {
        return require(session, "session");
    }

    public TicketRepository tickets() {
        return require(tickets, "ticket repository");
    }

    public ContractRepository contracts() {
        return require(contracts, "contract repository");
    }

    public SettlementRepository settlements() {
        return require(settlements, "settlement repository");
    }

    public PartyRepository parties() {
        return require(parties, "party repository");
    }

    public ComplaintRepository complaints() {
        return require(complaints, "complaint repository");
    }

    public LedgerRepository ledger() {
        return require(ledger, "ledger repository");
    }

    public boolean isOpen() {
        return session != null;
    }

    /** Whether the work so far has been committed, and nothing rolled back since. */
    public boolean isCommitted() {
        return committed;
    }

    /**
     * Make the work so far stick, and begin the next transaction.
     *
     * @throws ConflictError when the unit is not open
     */
    public void commit() {
        if (session == null) {
            throw new ConflictError("cannot commit a unit of work that is not open");
        }
        EntityTransaction transaction = session.getTransaction();
        transaction.commit();
        committed = true;
        transaction.begin();
    }

    /**
     * Undo the work since the last commit, forget every row that was read, and begin again. Nothing
     * happens when the unit is not open.
     */
    public void rollback() {
        if (session != null) {
            EntityTransaction transaction = session.getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
            session.clear();
            transaction.begin();
        }
        committed = false;
    }

    /**
     * Write what is pending to the database without committing it.
     *
     * @throws ConflictError when the unit is not open
     */
    public void flush() {
        if (session == null) {
            throw new ConflictError("cannot flush a unit of work that is not open");
        }
        session.flush();
    }

    /** Roll back whatever was not committed and close the session. Safe to call twice. */
    @Override
    public void close() {
        if (session == null) {
            return;
        }
        try {
            EntityTransaction transaction = session.getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
        } finally {
            session.close();
            session = null;
            tickets = null;
            contracts = null;
            settlements = null;
            parties = null;
            complaints = null;
            ledger = null;
        }
    }

    private static <T> T require(T thing, String name) {
        if (thing == null) {
            throw new ConflictError("the " + name + " are only available inside an open unit of work");
        }
        return thing;
    }
}
