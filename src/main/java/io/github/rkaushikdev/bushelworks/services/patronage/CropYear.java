package io.github.rkaushikdev.bushelworks.services.patronage;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The first and last days of a crop year, named for the autumn it opens in.
 *
 * @param opens the first of September
 * @param closes the last of August the year after
 */
public record CropYear(LocalDate opens, LocalDate closes) {

    public CropYear {
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
    }
}
