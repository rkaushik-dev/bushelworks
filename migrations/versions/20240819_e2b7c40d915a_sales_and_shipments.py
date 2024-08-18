"""sales and shipments

Revision ID: e2b7c40d915a
Revises: d15e0a73b6c9
Created: 2024-08-19

Destination weight and grade are nullable. The buyer's settlement arrives a
fortnight after the truck and a shipment without one is the normal state, not a
half written row.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "e2b7c40d915a"
down_revision: str | None = "d15e0a73b6c9"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)
PRICE = sa.Numeric(12, 4)
BUSHELS = sa.Numeric(14, 4)
POUNDS = sa.Numeric(12, 0)
PERCENT = sa.Numeric(7, 4)


def upgrade() -> None:
    op.create_table(
        "sale_contract",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(24), nullable=False),
        sa.Column("kind", sa.String(12), nullable=False),
        sa.Column("buyer", sa.String(80), nullable=False),
        sa.Column("destination", sa.String(16), nullable=False),
        sa.Column("carrier", sa.String(8), nullable=False, server_default="truck"),
        sa.Column("commodity_code", sa.String(8), nullable=False),
        sa.Column("origin_code", sa.String(12), nullable=False),
        sa.Column("written_on", sa.Date(), nullable=False),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("shipped", BUSHELS, nullable=False, server_default="0"),
        sa.Column("window_opens", sa.Date(), nullable=False),
        sa.Column("window_closes", sa.Date(), nullable=False),
        sa.Column("futures_symbol", sa.String(12)),
        sa.Column("futures_price", PRICE),
        sa.Column("basis", PRICE),
        sa.Column("flat_price", PRICE),
        sa.Column("tolerance_percent", PERCENT, nullable=False, server_default="2"),
        sa.Column("freight_per_bushel", PRICE, nullable=False, server_default="0"),
        sa.Column("spec_max_moisture", PERCENT, nullable=False),
        sa.Column("spec_min_test_weight", PERCENT, nullable=False),
        sa.Column("spec_max_damage", PERCENT, nullable=False),
        sa.Column("spec_max_foreign", PERCENT, nullable=False),
        sa.Column("status", sa.String(12), nullable=False, server_default="open"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("number", name="uq_sale_contract_number"),
        sa.CheckConstraint("bushels > 0", name="ck_sale_contract_bushels_positive"),
        sa.CheckConstraint("shipped >= 0", name="ck_sale_contract_shipped_not_negative"),
        sa.CheckConstraint(
            "window_closes >= window_opens", name="ck_sale_contract_window_ordered"
        ),
        sa.CheckConstraint(
            "freight_per_bushel >= 0", name="ck_sale_contract_freight_not_negative"
        ),
    )
    op.create_index("ix_sale_contract_buyer", "sale_contract", ["buyer", "status"])
    op.create_index(
        "ix_sale_contract_commodity_code",
        "sale_contract",
        ["commodity_code", "window_closes"],
    )

    op.create_table(
        "shipment",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "sale_id",
            sa.Integer(),
            sa.ForeignKey("sale_contract.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("reference", sa.String(24), nullable=False),
        sa.Column("shipped_on", sa.Date(), nullable=False),
        sa.Column("carrier_id", sa.String(24)),
        sa.Column("origin_pounds", POUNDS, nullable=False),
        sa.Column("origin_bushels", BUSHELS, nullable=False),
        sa.Column("destination_pounds", POUNDS),
        sa.Column("destination_bushels", BUSHELS),
        sa.Column("destination_grade", sa.String(8)),
        sa.Column("settled_on", sa.Date()),
        sa.Column("proceeds", MONEY),
        sa.Column("rejected", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("rejection_reason", sa.String(200)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("sale_id", "reference", name="uq_shipment_sale_id"),
        sa.CheckConstraint(
            "origin_pounds > 0", name="ck_shipment_origin_pounds_positive"
        ),
    )
    op.create_index("ix_shipment_shipped_on", "shipment", ["shipped_on"])
    op.create_index("ix_shipment_carrier_id", "shipment", ["carrier_id"])


def downgrade() -> None:
    op.drop_index("ix_shipment_carrier_id", table_name="shipment")
    op.drop_index("ix_shipment_shipped_on", table_name="shipment")
    op.drop_table("shipment")
    op.drop_index("ix_sale_contract_commodity_code", table_name="sale_contract")
    op.drop_index("ix_sale_contract_buyer", table_name="sale_contract")
    op.drop_table("sale_contract")
