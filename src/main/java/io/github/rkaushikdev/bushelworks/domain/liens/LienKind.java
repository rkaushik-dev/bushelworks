package io.github.rkaushikdev.bushelworks.domain.liens;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * The three arrangements a lienholder can file, which are not the same.
 *
 * <ul>
 *   <li>{@link #JOINT_PAYEE} puts both names on one cheque. The producer cannot cash it without the
 *       bank, and the bank cannot cash it without the producer.
 *   <li>{@link #ASSIGNMENT} sends an agreed amount straight to the assignee off the top, and the
 *       producer gets a cheque for what is left.
 *   <li>{@link #NOTICE_ONLY} is a bank telling the house it has an interest without asking for
 *       anything. It goes on the file and changes nothing about the cheque.
 * </ul>
 */
public enum LienKind implements ValueEnum {
    JOINT_PAYEE("joint_payee"),
    ASSIGNMENT("assignment"),
    NOTICE_ONLY("notice_only");

    private final String value;

    LienKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static LienKind of(String value) {
        return ValueEnum.of(LienKind.class, value);
    }
}
