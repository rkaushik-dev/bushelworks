package io.github.rkaushikdev.bushelworks.services.audit;

/**
 * The kinds of document the audit trail knows about.
 *
 * <p>Plain text, like the verbs in {@link Action}: an entry is looked up by kind and number as they
 * were written, and a kind the trail has not listed here (a sale, say) is still a kind.
 */
public final class Document {

    public static final String TICKET = "ticket";
    public static final String CONTRACT = "contract";
    public static final String SETTLEMENT = "settlement";
    public static final String STORAGE_LOT = "storage_lot";
    public static final String RECEIPT = "warehouse_receipt";
    public static final String PAYMENT = "payment";

    private Document() {}
}
