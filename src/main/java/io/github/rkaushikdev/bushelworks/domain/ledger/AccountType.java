package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * The five kinds of account, each of which grows on one side.
 *
 * <p>Assets and expenses grow on the debit side and everything else on the credit side, which is
 * why a debit to a revenue account reads as a reduction on the trial balance.
 */
public enum AccountType implements ValueEnum {
    ASSET("asset"),
    LIABILITY("liability"),
    EQUITY("equity"),
    REVENUE("revenue"),
    EXPENSE("expense");

    private final String value;

    AccountType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** Which side increases this kind of account. */
    public Side normalSide() {
        return this == ASSET || this == EXPENSE ? Side.DEBIT : Side.CREDIT;
    }

    /** Assets, liabilities and equity carry forward; revenue and expense close out each year. */
    public boolean onBalanceSheet() {
        return this == ASSET || this == LIABILITY || this == EQUITY;
    }

    public static AccountType of(String value) {
        return ValueEnum.of(AccountType.class, value);
    }
}
