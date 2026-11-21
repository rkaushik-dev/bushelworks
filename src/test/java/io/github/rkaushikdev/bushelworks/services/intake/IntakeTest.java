package io.github.rkaushikdev.bushelworks.services.intake;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.services.intake.Intake.dryingCharge;
import static io.github.rkaushikdev.bushelworks.services.intake.Intake.receive;
import static io.github.rkaushikdev.bushelworks.services.intake.Intake.restateFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeTest {

    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void fileTheHouse() {
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    static Map<Factor, BigDecimal> sample(Object... factorsAndValues) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        for (int index = 0; index < factorsAndValues.length; index += 2) {
            sample.put((Factor) factorsAndValues[index], d((String) factorsAndValues[index + 1]));
        }
        return sample;
    }

    @Test
    void aCleanWetLoadComesThroughIntakeWhole() {
        GradedLoad load = receive(cornTicket, rules);
        assertThat(load.netPounds()).isEqualTo(d("52240"));
        assertThat(load.cleanBushels()).isEqualTo(d("932.8571"));
        // 3.2 points over base at 1.18 is 3.776 percent
        assertThat(load.shrinkPercent()).isEqualTo(d("3.7760"));
        assertThat(load.dryBushels()).isEqualTo(d("897.6324"));
        assertThat(load.shrinkBushels()).isEqualTo(d("35.2247"));
        assertThat(load.cleanBushels()).isEqualTo(load.dryBushels().add(load.shrinkBushels()));
    }

    @Test
    void intakeGradesTheSample() {
        GradedLoad load = receive(cornTicket, rules);
        assertThat(load.grade().grade()).isSameAs(NumericalGrade.US_1);
        assertThat(load.isSampleGrade()).isFalse();
    }

    @Test
    void moisturePointsAreMeasuredAgainstTheFiledBase() {
        assertThat(receive(cornTicket, rules).moisturePoints()).isEqualTo(d("3.2"));
    }

    @Test
    void aDryLoadNeitherShrinksNorDries() {
        ScaleTicket dry = cornTicket.toBuilder()
                .sample(HouseFixtures.cornSampleWith(Factor.MOISTURE, "14.1"))
                .build();
        GradedLoad load = receive(dry, rules);
        assertThat(load.shrinkPercent()).isEqualTo(d("0.0000"));
        assertThat(load.dryBushels()).isEqualTo(load.cleanBushels());
        assertThat(dryingCharge(load, rules)).isEqualTo(d("0.00"));
    }

    @Test
    void discountsComeBackPerBushelAndItemised() {
        Map<Factor, BigDecimal> readings = HouseFixtures.cornSample();
        readings.put(Factor.TEST_WEIGHT, d("53.4"));
        readings.put(Factor.BCFM, d("3.35"));
        GradedLoad load = receive(cornTicket.toBuilder().sample(readings).build(), rules);
        assertThat(load.discountDetail()).isEqualTo(Map.of(Factor.TEST_WEIGHT, d("0.0200"), Factor.BCFM, d("0.0070")));
        assertThat(load.discountRate()).isEqualTo(d("0.0270"));
    }

    @Test
    void aCleanLoadCarriesNoDiscount() {
        GradedLoad load = receive(cornTicket, rules);
        assertThat(load.discountRate()).isEqualTo(d("0.0000"));
        assertThat(load.discountDetail()).isEmpty();
    }

    @Test
    void dockageIsOutBeforeBushelsAreCounted() {
        GradedLoad load =
                receive(cornTicket.toBuilder().dockagePercent(d("2.0")).build(), rules);
        assertThat(load.dockagePounds()).isEqualTo(d("1045"));
        assertThat(load.grossBushels()).isEqualTo(d("932.8571"));
        assertThat(load.cleanBushels()).isEqualTo(d("914.1964"));
    }

    @Test
    void dryingIsChargedOnTheWetBushelsByDefault() {
        GradedLoad load = receive(cornTicket, rules);
        // 2 points at 0.03 plus 1.2 at 0.045 is 0.114 per bushel
        assertThat(dryingCharge(load, rules)).isEqualTo(d("106.35"));
    }

    @Test
    void dryingCanBeChargedOnTheDryBushelsInstead() {
        GradedLoad load = receive(cornTicket, rules);
        assertThat(dryingCharge(load, rules, true)).isEqualTo(d("102.33"));
    }

    @Test
    void aHouseWithNoDryerFiledChargesNothing() {
        ScaleTicket soy = cornTicket.toBuilder()
                .commodityCode("SOYB")
                .sample(sample(
                        Factor.TEST_WEIGHT, "56.0",
                        Factor.MOISTURE, "15.0",
                        Factor.FOREIGN_MATERIAL, "0.5",
                        Factor.DAMAGE_TOTAL, "1.0",
                        Factor.SPLITS, "8.0"))
                .build();
        GradedLoad load = receive(soy, rules);
        assertThat(dryingCharge(load, rules)).isEqualTo(d("0.00"));
    }

    @Test
    void aVoidedTicketIsRefused() {
        ScaleTicket voided = cornTicket.voidTicket("reweigh");
        assertThatThrownBy(() -> receive(voided, rules)).isInstanceOf(IntakeError.class);
    }

    @Test
    void aCommodityWithNoShrinkTableIsRefused() {
        ScaleTicket milo = cornTicket.toBuilder()
                .commodityCode("MILO")
                .sample(sample(
                        Factor.TEST_WEIGHT, "57.0",
                        Factor.MOISTURE, "13.0",
                        Factor.FOREIGN_MATERIAL, "1.0",
                        Factor.DAMAGE_TOTAL, "1.0"))
                .build();
        assertThatThrownBy(() -> receive(milo, rules)).isInstanceOf(IntakeError.class);
    }

    @Test
    void aTicketBeforeTheSeasonOpensIsRefused() {
        ScaleTicket early =
                cornTicket.toBuilder().scaleDate(LocalDate.of(2024, 7, 15)).build();
        assertThatThrownBy(() -> receive(early, rules)).isInstanceOf(IntakeError.class);
    }

    @Test
    void wheatFiledWithNoShrinkKeepsAllItsBushels() {
        ScaleTicket wheat = cornTicket.toBuilder()
                .commodityCode("HRW")
                .dockagePercent(d("1.5"))
                .sample(sample(
                        Factor.TEST_WEIGHT, "60.5",
                        Factor.MOISTURE, "14.8",
                        Factor.DOCKAGE, "1.5",
                        Factor.DAMAGE_TOTAL, "1.0",
                        Factor.SHRUNKEN_BROKEN, "1.0",
                        Factor.TOTAL_DEFECTS, "2.0"))
                .build();
        GradedLoad load = receive(wheat, rules);
        assertThat(load.shrinkPercent()).isEqualTo(d("0.0000"));
        assertThat(load.dryBushels()).isEqualTo(load.cleanBushels());
    }

    @Test
    void restatingAMoistureReadingRedoesOnlyTheShrink() {
        GradedLoad load = receive(cornTicket, rules);
        ShrinkSchedule schedule = ShrinkSchedule.of("CORN", d("15.0"))
                .withMethod(ShrinkMethod.TABLE)
                .withShrinkFactor(d("1.18"));
        GradedLoad corrected = restateFor(load, schedule, d("17.0"));
        assertThat(corrected.moisturePoints()).isEqualTo(d("2.0"));
        assertThat(corrected.shrinkPercent()).isEqualTo(d("2.3600"));
        assertThat(corrected.cleanBushels()).isEqualTo(load.cleanBushels());
        assertThat(corrected.grade()).isEqualTo(load.grade());
    }

    @Test
    void settledBushelsIsTheDryCount() {
        GradedLoad load = receive(cornTicket, rules);
        assertThat(load.settledBushels()).isEqualTo(load.dryBushels());
    }
}
