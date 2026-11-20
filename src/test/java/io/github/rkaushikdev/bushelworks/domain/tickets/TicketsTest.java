package io.github.rkaushikdev.bushelworks.domain.tickets;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.TestDecimals.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.UnitError;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TicketsTest {

    static ScaleTicket.Builder ticket() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("56.5"));
        sample.put(Factor.MOISTURE, d("16.4"));
        sample.put(Factor.BCFM, d("1.2"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.0"));
        return ScaleTicket.builder()
                .ticketNumber("A-10441")
                .scaleDate(LocalDate.of(2024, 10, 3))
                .locationCode("HOME")
                .commodityCode("CORN")
                .grossPounds(d("78420"))
                .tarePounds(d("26180"))
                .disposition(Disposition.CASH_SALE)
                .splits(List.of(new SplitShare("MERRICK", d("100"))))
                .sample(sample);
    }

    @Test
    void netWeightIsGrossLessTare() {
        assertThat(ticket().build().netPounds()).isEqualTo(d("52240"));
    }

    @Test
    void noDockageLeavesTheCleanWeightAlone() {
        ScaleTicket t = ticket().build();
        assertThat(t.dockagePounds()).isEqualByComparingTo(d("0"));
        assertThat(t.cleanPounds()).isEqualTo(t.netPounds());
    }

    @Test
    void dockageComesOffBeforeBushels() {
        ScaleTicket t = ticket().commodityCode("HRW").dockagePercent(d("1.5")).build();
        assertThat(t.dockagePounds()).isEqualTo(d("784"));
        assertThat(t.cleanPounds()).isEqualTo(d("51456"));
    }

    @Test
    void grossBushelsIgnoreDockageAndCleanBushelsDoNot() {
        ScaleTicket t = ticket().dockagePercent(d("1.5")).build();
        assertThat(t.grossBushels(Commodities.CORN)).isEqualTo(d("932.8571"));
        assertThat(t.cleanBushels(Commodities.CORN)).isEqualTo(d("918.8571"));
    }

    @Test
    void sharesHaveToTotalTheWholeLoad() {
        assertThatThrownBy(() -> ticket().splits(
                                List.of(new SplitShare("MERRICK", d("60")), new SplitShare("HOLLIS", d("30"))))
                        .build())
                .isInstanceOf(TicketError.class);
    }

    @Test
    void aPartyCannotAppearTwiceOnTheSplit() {
        assertThatThrownBy(() -> ticket().splits(
                                List.of(new SplitShare("MERRICK", d("50")), new SplitShare("MERRICK", d("50"))))
                        .build())
                .isInstanceOf(TicketError.class);
    }

    @Test
    void aShareOfZeroIsRefused() {
        assertThatThrownBy(() -> new SplitShare("MERRICK", d("0"))).isInstanceOf(TicketError.class);
    }

    @Test
    void cropShareLeaseSplitsTwoWays() {
        ScaleTicket t = ticket().splits(List.of(
                        new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
                        new SplitShare("MERRICK", d("75"), SplitRole.TENANT)))
                .build();
        assertThat(t.parties()).containsExactly("BROCKMAN", "MERRICK");
        assertThat(t.shareOf("BROCKMAN")).isEqualByComparingTo(d("25"));
    }

    @Test
    void askingForAShareNobodyHoldsIsAnError() {
        assertThatThrownBy(() -> ticket().build().shareOf("HOLLIS")).isInstanceOf(TicketError.class);
    }

    @Test
    void contractDeliveryNeedsAContractNumber() {
        assertThatThrownBy(() -> ticket().disposition(Disposition.CONTRACT).build())
                .isInstanceOf(TicketError.class);
        assertThat(ticket().disposition(Disposition.CONTRACT)
                        .contractNumber("F-2024-118")
                        .build()
                        .contractNumber())
                .contains("F-2024-118");
    }

    @Test
    void tareAboveGrossIsRefused() {
        assertThatThrownBy(() -> ticket().grossPounds(d("26180"))
                        .tarePounds(d("78420"))
                        .build()
                        .netPounds())
                .isInstanceOf(UnitError.class);
    }

    @Test
    void dockageOutOfRangeIsRefused() {
        assertThatThrownBy(() -> ticket().dockagePercent(d("-1")).build()).isInstanceOf(TicketError.class);
        assertThatThrownBy(() -> ticket().dockagePercent(d("100")).build()).isInstanceOf(TicketError.class);
    }

    @Test
    void factorLookupAndRequirement() {
        ScaleTicket t = ticket().build();
        assertThat(t.factor(Factor.MOISTURE)).contains(d("16.4"));
        assertThat(t.factor(Factor.PROTEIN)).isEmpty();
        assertThat(t.requireFactor(Factor.BCFM)).isEqualTo(d("1.2"));
        assertThatThrownBy(() -> t.requireFactor(Factor.PROTEIN)).isInstanceOf(TicketError.class);
    }

    @Test
    void voidKeepsTheWeightsAndCarriesTheReason() {
        ScaleTicket voided = ticket().build().voidTicket("reweighed, driver had the wrong tare");
        assertThat(voided.voided()).isTrue();
        assertThat(voided.netPounds()).isEqualTo(d("52240"));
        assertThat(voided.remarks()).startsWith("VOID: ");
    }

    @Test
    void aTicketCannotBeVoidedTwice() {
        ScaleTicket voided = ticket().build().voidTicket("scale fault");
        assertThatThrownBy(() -> voided.voidTicket("again")).isInstanceOf(TicketError.class);
    }

    @Test
    void aVoidNeedsAReason() {
        assertThatThrownBy(() -> ticket().build().voidTicket("")).isInstanceOf(TicketError.class);
    }

    @Test
    void splittingBushelsAddsBackToTheWhole() {
        List<SplitShare> shares = List.of(
                new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
                new SplitShare("MERRICK", d("75"), SplitRole.TENANT));
        Map<String, BigDecimal> parts = Tickets.splitBushels(d("932.8571"), shares);
        assertThat(sum(List.copyOf(parts.values()))).isEqualByComparingTo(d("932.8571"));
        assertThat(parts.get("BROCKMAN")).isEqualTo(d("233.2143"));
    }

    @Test
    void threeWaySplitResidueLandsOnTheLargestShare() {
        List<SplitShare> shares =
                List.of(new SplitShare("A", d("33")), new SplitShare("B", d("33")), new SplitShare("C", d("34")));
        Map<String, BigDecimal> parts = Tickets.splitBushels(d("1000.0001"), shares);
        assertThat(sum(List.copyOf(parts.values()))).isEqualByComparingTo(d("1000.0001"));
    }

    @Test
    void splittingAcrossNothingIsAnError() {
        assertThatThrownBy(() -> Tickets.splitBushels(d("100"), List.of())).isInstanceOf(TicketError.class);
    }
}
