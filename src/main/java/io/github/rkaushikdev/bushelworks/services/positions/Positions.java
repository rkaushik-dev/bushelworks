package io.github.rkaushikdev.bushelworks.services.positions;

import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building the daily position out of what is on file.
 *
 * <p>The position report is the one number the manager looks at before he answers the telephone to
 * the terminal, and it is assembled from several places that each know part of it. This part is the
 * purchase side: bushels bought forward and not yet delivered.
 */
public final class Positions {

    private Positions() {}

    /** Bushels bought forward across every site that have not arrived yet. */
    public static BigDecimal contractedBushels(UnitOfWork uow, String commodityCode, LocalDate through) {
        return contractedBushels(uow, commodityCode, through, null);
    }

    /**
     * Bushels bought forward that have not arrived yet: each open contract written by {@code
     * through} and still taking delivery on it, less what has been applied to it, floored at zero. A
     * contract delivered past its face amount does not turn into a short.
     *
     * <p>Two statements however many contracts are open, and one when none is.
     *
     * @param locationCode one site, or null for every site
     */
    public static BigDecimal contractedBushels(
            UnitOfWork uow, String commodityCode, LocalDate through, String locationCode) {
        String site = locationCode == null || locationCode.isEmpty() ? null : locationCode;
        TypedQuery<ContractRow> query = uow.session()
                .createQuery(
                        "select c from ContractRow c where c.commodityCode = :commodity and c.status = :status"
                                + " and c.writtenOn <= :through and c.windowCloses >= :through"
                                + (site == null ? "" : " and c.locationCode = :location")
                                + " order by c.id",
                        ContractRow.class)
                .setParameter("commodity", commodityCode)
                .setParameter("status", ContractStatus.OPEN.value())
                .setParameter("through", through);
        if (site != null) {
            query.setParameter("location", site);
        }
        List<ContractRow> rows = query.getResultList();
        if (rows.isEmpty()) {
            return Units.quantize(BigDecimal.ZERO, Units.BUSHEL_PLACES);
        }

        Map<Integer, BigDecimal> applied =
                appliedByContract(uow, rows.stream().map(ContractRow::getId).toList());
        BigDecimal outstanding = BigDecimal.ZERO;
        for (ContractRow row : rows) {
            BigDecimal remaining = row.getBushels().subtract(applied.getOrDefault(row.getId(), BigDecimal.ZERO));
            if (remaining.signum() > 0) {
                outstanding = outstanding.add(remaining);
            }
        }
        return Units.quantize(outstanding, Units.BUSHEL_PLACES);
    }

    /**
     * Bushels already applied and not reversed, per contract, in one statement. A contract nothing
     * is applied to is left out, and no contracts asked about is no statement at all.
     *
     * <p>This used to be one query per open contract. On a house with two hundred open contracts the
     * daily position was two hundred and six statements for a number that is one group by, and over
     * the office network share that was most of the four seconds the manager waited for his screen.
     */
    public static Map<Integer, BigDecimal> appliedByContract(UnitOfWork uow, Collection<Integer> contractIds) {
        if (contractIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BigDecimal> applied = new LinkedHashMap<>();
        for (Object[] total : uow.session()
                .createQuery(
                        "select a.contractId, sum(a.bushels) from ApplicationRow a"
                                + " where a.contractId in :contracts and a.reversedOn is null"
                                + " group by a.contractId order by a.contractId",
                        Object[].class)
                .setParameter("contracts", contractIds)
                .getResultList()) {
            applied.put((Integer) total[0], Columns.asDecimal(total[1]));
        }
        return applied;
    }
}
