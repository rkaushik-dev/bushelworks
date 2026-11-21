package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Purchase contracts.
 *
 * <p>Contracts are replaced whole rather than patched. A contract is a document and the office
 * amends it by writing a new one, so {@link #replace} writes every term and keeps only the row's own
 * key and timestamps.
 */
public final class ContractRepository {

    private final EntityManager session;

    public ContractRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * The stored row for one contract.
     *
     * @throws ContractNotFound when there is none with that number
     */
    public ContractRow row(String number) {
        return find(number).orElseThrow(() -> new ContractNotFound("no contract " + number));
    }

    /**
     * One contract, read back as the domain object.
     *
     * @throws ContractNotFound when there is none with that number
     */
    public PurchaseContract get(String number) {
        return Mappers.contractFromRow(row(number));
    }

    /**
     * Write a new contract and flush it.
     *
     * @throws ConflictError when the number is already taken
     */
    public ContractRow add(PurchaseContract contract) {
        if (find(contract.number()).isPresent()) {
            throw new ConflictError("contract " + contract.number() + " already exists");
        }
        ContractRow row = Mappers.contractToRow(contract);
        session.persist(row);
        session.flush();
        return row;
    }

    /**
     * Write an amended contract back over its row.
     *
     * <p>Contracts are amended rather than voided, because a hedge to arrive that has had its basis
     * set is the same contract, and the futures position is booked against its number.
     *
     * @throws ContractNotFound when there is no contract with that number to amend
     */
    public ContractRow replace(PurchaseContract contract) {
        ContractRow row = row(contract.number());
        Mappers.writeContract(contract, row);
        session.flush();
        return row;
    }

    /**
     * The open contracts a party holds for a commodity whose window takes delivery on {@code day},
     * the soonest closing first.
     */
    public List<PurchaseContract> openFor(String partyCode, String commodityCode, LocalDate day) {
        return session
                .createQuery(
                        "select c from ContractRow c where c.partyCode = :party and c.commodityCode = :commodity"
                                + " and c.status = :status and c.windowOpens <= :day and c.windowCloses >= :day"
                                + " order by c.windowCloses, c.number",
                        ContractRow.class)
                .setParameter("party", partyCode)
                .setParameter("commodity", commodityCode)
                .setParameter("status", ContractStatus.OPEN.value())
                .setParameter("day", day)
                .getResultList()
                .stream()
                .map(Mappers::contractFromRow)
                .toList();
    }

    /**
     * The bushels applied against a contract and not reversed.
     *
     * @throws ContractNotFound when there is none with that number
     */
    public BigDecimal appliedBushels(String number) {
        ContractRow row = row(number);
        BigDecimal total = session.createQuery(
                        "select sum(a.bushels) from ApplicationRow a"
                                + " where a.contractId = :contract and a.reversedOn is null",
                        BigDecimal.class)
                .setParameter("contract", row.getId())
                .getSingleResult();
        return Columns.asDecimal(total);
    }

    /**
     * Mark an open contract filled.
     *
     * @throws ConflictError when it is not open
     */
    public ContractRow close(String number) {
        ContractRow row = row(number);
        if (!row.getStatus().equals(ContractStatus.OPEN.value())) {
            throw new ConflictError("contract " + number + " is " + row.getStatus() + ", not open");
        }
        row.setStatus(ContractStatus.FILLED.value());
        session.flush();
        return row;
    }

    private Optional<ContractRow> find(String number) {
        return Queries.first(
                session.createQuery("select c from ContractRow c where c.number = :number", ContractRow.class)
                        .setParameter("number", number));
    }
}
