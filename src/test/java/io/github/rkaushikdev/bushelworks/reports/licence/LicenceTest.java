package io.github.rkaushikdev.bushelworks.reports.licence;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.licence.Licence.combine;
import static io.github.rkaushikdev.bushelworks.reports.licence.Licence.overCapacity;
import static io.github.rkaushikdev.bushelworks.reports.licence.Licence.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LicenceTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 11, 30);

    static LicenceReturn.Builder aReturn() {
        return LicenceReturn.builder()
                .locationCode("HOME")
                .licenceNumber("KS-1174")
                .asOf(AS_OF)
                .licensedCapacity(d("1450000"))
                .storageObligation(d("400000"))
                .deferredPrice(d("150000"))
                .receiptsOutstanding(d("50000"))
                .houseOwned(d("300000"));
    }

    @Test
    void aSiteWithNoCapacityIsRefused() {
        assertThatThrownBy(() -> aReturn().licensedCapacity(d("0")).build())
                .isInstanceOf(LicenceError.class)
                .hasMessage("HOME: no licensed capacity");
    }

    @Test
    void aNegativeComponentIsRefused() {
        assertThatThrownBy(() -> aReturn().storageObligation(d("-1")).build())
                .isInstanceOf(LicenceError.class)
                .hasMessage("HOME: storage_obligation is negative");
        assertThatThrownBy(() -> aReturn().houseOwned(d("-1")).build())
                .isInstanceOf(LicenceError.class)
                .hasMessage("HOME: house_owned is negative");
    }

    @Test
    void theObligationIsMoreThanTheStorage() {
        LicenceReturn one = aReturn().build();
        assertThat(one.obligation()).isEqualTo(d("600000.0000"));
        assertThat(one.obligation()).isGreaterThan(one.storageObligation());
    }

    @Test
    void theTotalHeldAddsTheHouseGrain() {
        assertThat(aReturn().build().totalHeld()).isEqualTo(d("900000.0000"));
    }

    @Test
    void theUnusedCapacityIsWhatIsLeft() {
        assertThat(aReturn().build().unusedCapacity()).isEqualTo(d("550000.0000"));
    }

    @Test
    void aNormalMonthIsNotOverCapacity() {
        LicenceReturn one = aReturn().build();
        assertThat(one.isOverCapacity()).isFalse();
        assertThat(one.isTight()).isFalse();
    }

    @Test
    void aFullHouseIsOverCapacity() {
        LicenceReturn one = aReturn().houseOwned(d("1000000")).build();
        assertThat(one.isOverCapacity()).isTrue();
        assertThat(one.isTight()).isTrue();
        assertThat(one.unusedCapacity()).isNegative();
    }

    @Test
    void aHouseCloseToTheLicenceIsTight() {
        LicenceReturn one = aReturn().houseOwned(d("820000")).build();
        assertThat(one.isOverCapacity()).isFalse();
        assertThat(one.isTight()).isTrue();
    }

    @Test
    void theTightMarginIsFivePercent() {
        assertThat(Licence.TIGHT_MARGIN).isEqualTo(d("0.05"));
    }

    @Test
    void thePercentageUsedIsOfTheLicence() {
        assertThat(aReturn().build().percentUsed()).isEqualTo(d("62.1"));
    }

    @Test
    void theReturnNamesTheSiteAndTheLicence() {
        String text = render(aReturn().build());
        assertThat(text)
                .contains("WAREHOUSE RETURN 2024-11-30")
                .contains("KS-1174")
                .contains("HOME");
    }

    @Test
    void theReturnFitsItsWidth() {
        String text = render(aReturn().build());
        assertThat(text.split("\n", -1))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(72));
    }

    @Test
    void theReturnShowsTheObligationSeparately() {
        String text = render(aReturn().build());
        assertThat(text).contains("total obligation").contains("house owned");
    }

    @Test
    void anOverCapacityReturnSaysSoLoudly() {
        assertThat(render(aReturn().houseOwned(d("1000000")).build())).contains("OVER LICENSED CAPACITY");
    }

    @Test
    void aTightReturnCarriesANote() {
        assertThat(render(aReturn().houseOwned(d("820000")).build())).contains("Within five percent");
    }

    @Test
    void aComfortableReturnCarriesNoNote() {
        String text = render(aReturn().build());
        assertThat(text).doesNotContain("Within five percent").doesNotContain("OVER LICENSED");
    }

    @Test
    void theReturnIsLaidOutInItsColumns() {
        LicenceReturn tight = aReturn()
                .storageObligation(d("400000.25"))
                .houseOwned(d("820000"))
                .build();
        assertThat(render(tight))
                .isEqualTo(String.join(
                        "\n",
                        "                          BUSHELWORKS ELEVATOR",
                        "                      WAREHOUSE RETURN 2024-11-30",
                        "=".repeat(72),
                        "Site          HOME",
                        "Licence       KS-1174",
                        "-".repeat(72),
                        "storage obligation                         400000.25",
                        "deferred price                                150000",
                        "receipts outstanding                           50000",
                        "total obligation                         600000.2500",
                        "-".repeat(72),
                        "house owned                                   820000",
                        "total held                              1420000.2500",
                        "licensed capacity                            1450000",
                        "unused                                    29999.7500",
                        "used                                           97.9%",
                        "",
                        "Within five percent of the licence."));
        String over = render(tight.toBuilder().houseOwned(d("1000000")).build(), "NORTH STAR CO-OP");
        assertThat(over)
                .startsWith("                            NORTH STAR CO-OP\n")
                .contains("unused                                  -150000.2500\n")
                .contains("used                                          110.3%\n")
                .endsWith("\n\nOVER LICENSED CAPACITY. This has to be explained.");
    }

    @Test
    void twoSitesCombineIntoOneReturn() {
        LicenceReturn other = aReturn()
                .locationCode("LURAY")
                .licenceNumber("KS-1175")
                .licensedCapacity(d("620000"))
                .storageObligation(d("100000"))
                .deferredPrice(d("0"))
                .receiptsOutstanding(d("0"))
                .houseOwned(d("200000"))
                .build();
        LicenceReturn whole = combine(List.of(aReturn().build(), other), AS_OF);
        assertThat(whole.locationCode()).isEqualTo("*");
        assertThat(whole.licensedCapacity()).isEqualTo(d("2070000"));
        assertThat(whole.totalHeld()).isEqualTo(d("1200000.0000"));
    }

    @Test
    void theCombinedReturnListsBothLicences() {
        LicenceReturn other =
                aReturn().locationCode("LURAY").licenceNumber("KS-1175").build();
        assertThat(combine(List.of(aReturn().build(), other), AS_OF).licenceNumber())
                .isEqualTo("KS-1174, KS-1175");
        assertThat(combine(List.of(other, aReturn().build()), AS_OF).licenceNumber())
                .isEqualTo("KS-1174, KS-1175");
    }

    @Test
    void combiningNothingIsRefused() {
        assertThatThrownBy(() -> combine(List.of(), AS_OF))
                .isInstanceOf(LicenceError.class)
                .hasMessage("nothing to combine");
    }

    @Test
    void combiningDifferentMonthsIsRefused() {
        List<LicenceReturn> returns = List.of(
                aReturn().build(), aReturn().asOf(LocalDate.of(2024, 10, 31)).build());
        assertThatThrownBy(() -> combine(returns, AS_OF))
                .isInstanceOf(LicenceError.class)
                .hasMessage("cannot combine returns from different months");
    }

    @Test
    void theOverCapacitySitesAreFound() {
        LicenceReturn full =
                aReturn().locationCode("LURAY").houseOwned(d("1000000")).build();
        List<LicenceReturn> found = overCapacity(List.of(aReturn().build(), full));
        assertThat(found).extracting(LicenceReturn::locationCode).containsExactly("LURAY");
    }
}
