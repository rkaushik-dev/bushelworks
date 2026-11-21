package io.github.rkaushikdev.bushelworks.domain.liens;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turning one settlement net into the cheques that go out, with the lienholders on them. */
public final class Liens {

    private Liens() {}

    /** Everything that bites on this commodity on this day, oldest filing first. */
    public static List<Interest> liveInterests(List<Interest> interests, String commodityCode, LocalDate day) {
        return interests.stream()
                .filter(one -> one.appliesTo(commodityCode, day))
                .sorted(Comparator.comparing(Interest::filedOn).thenComparing(Interest::reference))
                .toList();
    }

    /**
     * Turn one net into the cheques that actually go out.
     *
     * <p>Assignments come off the top, oldest filing first, each capped at what it is written for.
     * Whatever is left goes to the producer, jointly with anybody who filed as a joint payee. A
     * notice only interest changes nothing.
     */
    public static List<Payment> splitPayment(
            String partyCode, BigDecimal net, List<Interest> interests, String commodityCode, LocalDate day) {
        BigDecimal payable = Money.amount(net);
        if (payable.signum() <= 0) {
            return List.of();
        }
        List<Interest> live = liveInterests(interests, commodityCode, day);
        List<Payment> payments = new ArrayList<>();
        BigDecimal remaining = payable;
        for (Interest one : live) {
            if (one.kind() != LienKind.ASSIGNMENT || remaining.signum() <= 0) {
                continue;
            }
            BigDecimal take = Money.amount(Decimals.min(one.amountLimit().orElse(BigDecimal.ZERO), remaining));
            if (take.signum() <= 0) {
                continue;
            }
            payments.add(new Payment(one.holder(), List.of(), take, "assignment " + one.reference()));
            remaining = Money.amount(remaining.subtract(take));
        }
        if (remaining.signum() <= 0) {
            return payments;
        }
        List<String> joint = live.stream()
                .filter(one -> one.kind() == LienKind.JOINT_PAYEE)
                .map(Interest::holder)
                .toList();
        payments.add(new Payment(partyCode, joint, remaining, "settlement"));
        return payments;
    }

    /** Refuse unless the cheques add back to the settlement, to the cent. */
    public static void checkBalances(List<Payment> payments, BigDecimal net) {
        BigDecimal written = Money.total(payments.stream().map(Payment::dollars).toList());
        BigDecimal expected = Money.amount(net);
        if (expected.signum() <= 0) {
            expected = Money.amount("0.00");
        }
        if (written.compareTo(expected) != 0) {
            throw new LienError("cheques total " + written + ", which is not the " + expected + " settled");
        }
    }

    /** Interests on file that ask for nothing, for the statement footer. */
    public static List<Interest> notices(List<Interest> interests, LocalDate day) {
        return interests.stream()
                .filter(one -> one.kind() == LienKind.NOTICE_ONLY && one.isLiveOn(day))
                .toList();
    }
}
