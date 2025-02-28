from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.hedging import (
    CONTRACT_BUSHELS,
    Account,
    HedgeError,
    Lot,
    Side,
    contracts_for,
    hedge_gap,
    months_on,
    roll,
    roll_cost,
)
from bushelworks.domain.pricing import FuturesMonth
from bushelworks.errors import ConflictError

CZ4 = FuturesMonth("CORN", "Z", 2024)
CH5 = FuturesMonth("CORN", "H", 2025)
FILLED = date(2024, 10, 3)


def a_lot(**overrides) -> Lot:
    base = dict(
        reference="F-0001",
        month=CZ4,
        side=Side.SHORT,
        contracts=10,
        price=Decimal("4.5750"),
        filled_on=FILLED,
    )
    base.update(overrides)
    return Lot(**base)


def test_a_fill_needs_contracts_and_a_price():
    with pytest.raises(HedgeError):
        a_lot(contracts=0)
    with pytest.raises(HedgeError):
        a_lot(price=Decimal("0"))


def test_a_lot_knows_its_bushels():
    assert a_lot().bushels == Decimal("50000")
    assert Decimal("5000") == CONTRACT_BUSHELS


def test_a_short_counts_negative():
    assert a_lot().signed_contracts == -10
    assert a_lot(side=Side.LONG).signed_contracts == 10


def test_an_empty_account_is_flat():
    account = Account()
    assert account.net_contracts() == 0
    assert account.by_month() == {}
    assert not account.is_short


def test_the_account_nets_by_month():
    account = Account()
    account.add(a_lot())
    account.add(a_lot(reference="F-0002", month=CH5, contracts=4))
    assert account.by_month() == {CZ4: -10, CH5: -4}
    assert account.net_contracts() == -14
    assert account.net_bushels() == Decimal("-70000")
    assert account.is_short


def test_a_month_that_flattens_drops_off():
    account = Account()
    account.add(a_lot())
    account.add(a_lot(reference="F-0002", side=Side.LONG, contracts=10))
    assert account.by_month() == {}
    assert account.net_contracts() == 0


def test_the_same_reference_twice_is_a_conflict():
    account = Account()
    account.add(a_lot())
    with pytest.raises(ConflictError):
        account.add(a_lot())


def test_the_average_price_is_weighted_by_contracts():
    account = Account()
    account.add(a_lot(contracts=10, price=Decimal("4.5000")))
    account.add(a_lot(reference="F-0002", contracts=30, price=Decimal("4.7000")))
    assert account.average_price(CZ4) == Decimal("4.6500")


def test_a_month_with_nothing_on_has_no_average():
    with pytest.raises(HedgeError):
        Account().average_price(CZ4)


def test_a_short_gains_when_the_market_falls():
    account = Account()
    account.add(a_lot())
    value = account.open_value({CZ4: Decimal("4.4750")})
    assert value == Decimal("5000.00")


def test_a_short_loses_when_the_market_rises():
    account = Account()
    account.add(a_lot())
    assert account.open_value({CZ4: Decimal("4.6750")}) == Decimal("-5000.00")


def test_a_month_with_no_settlement_price_is_refused():
    account = Account()
    account.add(a_lot())
    with pytest.raises(HedgeError):
        account.open_value({})


def test_a_flat_account_is_worth_nothing():
    assert Account().open_value({}) == Decimal("0.00")


def test_contracts_are_whole_and_rounded_toward_zero():
    assert contracts_for(Decimal("50000")) == 10
    assert contracts_for(Decimal("52400")) == 10
    assert contracts_for(Decimal("-52400")) == -10


def test_the_hedge_gap_says_how_many_more_to_sell():
    account = Account()
    account.add(a_lot(contracts=5))
    assert hedge_gap(Decimal("50000"), account) == -5


def test_a_fully_hedged_position_has_no_gap():
    account = Account()
    account.add(a_lot(contracts=10))
    assert hedge_gap(Decimal("50000"), account) == 0


def test_an_over_hedged_position_says_to_buy_back():
    account = Account()
    account.add(a_lot(contracts=12))
    assert hedge_gap(Decimal("50000"), account) == 2


def test_rolling_a_month_that_is_not_on_is_refused():
    with pytest.raises(HedgeError):
        roll(Account(), CZ4, Decimal("4.57"), Decimal("4.70"), FILLED)


def test_the_roll_cost_is_the_spread_over_the_bushels():
    account = Account()
    account.add(a_lot())
    cost = roll_cost(account, CZ4, Decimal("4.5750"), Decimal("4.7000"))
    assert cost == Decimal("-6250.00")


def test_rolling_closes_the_near_month_and_opens_the_far_one():
    account = Account()
    account.add(a_lot())
    lots = roll(account, CZ4, Decimal("4.5750"), Decimal("4.7000"), date(2024, 11, 25))
    assert lots[0].side is Side.LONG
    assert lots[1].side is Side.SHORT
    assert account.by_month() == {CH5: -10}


def test_rolling_a_long_position_goes_the_other_way():
    account = Account()
    account.add(a_lot(side=Side.LONG))
    lots = roll(account, CZ4, Decimal("4.5750"), Decimal("4.7000"), date(2024, 11, 25))
    assert lots[0].side is Side.SHORT
    assert lots[1].side is Side.LONG
    assert account.by_month() == {CH5: 10}


def test_the_months_on_are_in_delivery_order():
    account = Account()
    account.add(a_lot(month=CH5))
    account.add(a_lot(reference="F-0002", month=CZ4))
    assert list(months_on(account)) == [CZ4, CH5]


def test_the_roll_cost_of_a_month_that_is_not_on_is_refused():
    with pytest.raises(HedgeError):
        roll_cost(Account(), CZ4, Decimal("4.57"), Decimal("4.70"))
