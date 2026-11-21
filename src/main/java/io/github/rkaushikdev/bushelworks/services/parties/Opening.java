package io.github.rkaushikdev.bushelworks.services.parties;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A new account for the producer file, as the office keys it.
 *
 * <pre>{@code
 * Parties.openAccount(uow, Opening.of("MERRICK", "Merrick Farms LLC").withTaxId("48-1234567"));
 * }</pre>
 *
 * @param code what the office calls the producer
 * @param name the name cheques are written to
 * @param stateCode the state the producer is in, Kansas unless said otherwise
 * @param sortName the name the pick list sorts by, the name itself when there is none
 * @param taxId the number the year end return is filed under
 * @param openedOn the day the account opened, today when there is none
 */
public record Opening(
        String code,
        String name,
        String stateCode,
        Optional<String> sortName,
        Optional<String> taxId,
        Optional<LocalDate> openedOn) {

    public Opening {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(stateCode, "stateCode");
        Objects.requireNonNull(sortName, "sortName");
        Objects.requireNonNull(taxId, "taxId");
        Objects.requireNonNull(openedOn, "openedOn");
    }

    /** An account in Kansas, sorted by its name, opened today. */
    public static Opening of(String code, String name) {
        return new Opening(code, name, "KS", Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Opening withStateCode(String value) {
        return new Opening(code, name, value, sortName, taxId, openedOn);
    }

    /** Null or empty sorts by the name. */
    public Opening withSortName(String value) {
        return new Opening(code, name, stateCode, Optional.ofNullable(value), taxId, openedOn);
    }

    /** Null clears it. */
    public Opening withTaxId(String value) {
        return new Opening(code, name, stateCode, sortName, Optional.ofNullable(value), openedOn);
    }

    /** Null means today. */
    public Opening withOpenedOn(LocalDate value) {
        return new Opening(code, name, stateCode, sortName, taxId, Optional.ofNullable(value));
    }
}
