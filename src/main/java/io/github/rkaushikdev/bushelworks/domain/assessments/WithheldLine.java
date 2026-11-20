package io.github.rkaushikdev.bushelworks.domain.assessments;

import java.math.BigDecimal;

/**
 * One assessment as it appears on the settlement statement.
 *
 * @param code the levy's code
 * @param name the levy's name
 * @param authority the commission it goes to
 * @param dollars what was withheld
 * @param refundable whether the producer may ask for it back
 */
public record WithheldLine(String code, String name, String authority, BigDecimal dollars, boolean refundable) {}
