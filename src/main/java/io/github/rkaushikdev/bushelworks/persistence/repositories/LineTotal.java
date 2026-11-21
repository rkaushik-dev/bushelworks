package io.github.rkaushikdev.bushelworks.persistence.repositories;

import java.math.BigDecimal;

/**
 * What one statement carries of one kind of line.
 *
 * <p>{@code quantity} is only meaningful on the gross lines, where it is bushels. A discount line
 * has a quantity too, but it is the bushels the rate was applied to rather than bushels delivered,
 * so adding it up gives a number that looks like volume and is not.
 *
 * @param settlementId the statement's key
 * @param kind the line kind, as stored
 * @param dollars the dollars on those lines
 * @param quantity the quantities on those lines, zero when none carried one
 */
public record LineTotal(int settlementId, String kind, BigDecimal dollars, BigDecimal quantity) {}
