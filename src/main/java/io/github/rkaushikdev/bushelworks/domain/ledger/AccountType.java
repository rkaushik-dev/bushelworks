package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * The five kinds of account, each of which grows on one side.
 *
 * <p>Assets and expenses grow on the debit side and everything else on the credit side, which is
 * why a debit to a revenue account reads as a reduction on the trial balance. A filed chart numbers
 * each kind in its own thousand, assets in the ones through revenue in the fours and expense in the
 * fives, so a code says what kind of account it is before anybody looks it up.
 */
public enum AccountType implements ValueEnum {
    ASSET("asset", '1'),
    LIABILITY("liability", '2'),
    EQUITY("equity", '3'),
    REVENUE("revenue", '4'),
    EXPENSE("expense", '5');

    private final String value;
    private final char leadingDigit;

    AccountType(String value, char leadingDigit) {
        this.value = value;
        this.leadingDigit = leadingDigit;
    }

    /** The digit a filed chart's codes for this kind of account start with. */
    public char leadingDigit() {
        return leadingDigit;
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
