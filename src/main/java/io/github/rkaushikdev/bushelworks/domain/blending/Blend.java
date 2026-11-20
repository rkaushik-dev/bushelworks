package io.github.rkaushikdev.bushelworks.domain.blending;

import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.Grading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What comes out of mixing several lots.
 *
 * @param bushels the whole mixture
 * @param sample what the mixture is predicted to test
 * @param lots the labels of the lots that went in
 */
public record Blend(BigDecimal bushels, Map<Factor, BigDecimal> sample, List<String> lots) {

    public Blend {
        sample = Ordered.copyOf(sample);
        lots = List.copyOf(lots);
    }

    public GradeResult grade(Commodity commodity) {
        return Grading.determine(commodity, sample);
    }
}
