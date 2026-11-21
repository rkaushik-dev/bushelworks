package io.github.rkaushikdev.bushelworks.services.audit;

/**
 * The verbs the audit trail records. A new one is added here rather than typed at the call site.
 *
 * <p>They are plain text rather than an enumeration because the trail stores and reads them as
 * text, and a row written by any version of the system has to read back.
 */
public final class Action {

    public static final String RECORDED = "recorded";
    public static final String VOIDED = "voided";
    public static final String AMENDED = "amended";
    public static final String PRICED = "priced";
    public static final String APPLIED = "applied";
    public static final String REVERSED = "reversed";
    public static final String SETTLED = "settled";
    public static final String PAID = "paid";
    public static final String ISSUED = "issued";
    public static final String CANCELLED = "cancelled";
    public static final String ACCRUED = "accrued";

    private Action() {}
}
