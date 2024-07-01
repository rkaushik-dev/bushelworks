"""contract and settlement tables

Revision ID: b47d9e15c082
Revises: 8c1b4f0a2d31
Created: 2024-06-20

The trading side. Split from the first migration because the scale house went
live on weights alone for the first fortnight, with settlement done on the old
system, and the two schemas had to coexist.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "b47d9e15c082"
down_revision: str | None = "8c1b4f0a2d31"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)
PRICE = sa.Numeric(12, 4)
BUSHELS = sa.Numeric(14, 4)
PERCENT = sa.Numeric(7, 4)


def upgrade() -> None:
    op.create_table(
        "purchase_contract",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(24), nullable=False),
        sa.Column("kind", sa.String(12), nullable=False),
        sa.Column("party_code", sa.String(24), nullable=False),
        sa.Column("commodity_code", sa.String(8), nullable=False),
        sa.Column("location_code", sa.String(12), nullable=False),
        sa.Column("written_on", sa.Date(), nullable=False),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("window_opens", sa.Date(), nullable=False),
        sa.Column("window_closes", sa.Date(), nullable=False),
        sa.Column("futures_symbol", sa.String(12)),
        sa.Column("futures_price", PRICE),
        sa.Column("basis", PRICE),
        sa.Column("flat_price", PRICE),
        sa.Column("tolerance_percent", PERCENT, nullable=False, server_default="2"),
        sa.Column(
            "service_rate_per_bushel", PRICE, nullable=False, server_default="0"
        ),
        sa.Column("status", sa.String(12), nullable=False, server_default="open"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("number", name="uq_purchase_contract_number"),
        sa.CheckConstraint("bushels > 0", name="ck_purchase_contract_bushels_positive"),
        sa.CheckConstraint(
            "window_closes >= window_opens", name="ck_purchase_contract_window_ordered"
        ),
        sa.CheckConstraint(
            "tolerance_percent >= 0 AND tolerance_percent <= 25",
            name="ck_purchase_contract_tolerance_range",
        ),
    )
    op.create_index(
        "ix_purchase_contract_party_code", "purchase_contract", ["party_code", "status"]
    )
    op.create_index(
        "ix_purchase_contract_window_closes", "purchase_contract", ["window_closes"]
    )

    op.create_table(
        "contract_application",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "ticket_id", sa.Integer(), sa.ForeignKey("scale_ticket.id"), nullable=False
        ),
        sa.Column(
            "contract_id",
            sa.Integer(),
            sa.ForeignKey("purchase_contract.id"),
            nullable=False,
        ),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("applied_on", sa.Date(), nullable=False),
        sa.Column("reversed_on", sa.Date()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "ticket_id", "contract_id", name="uq_contract_application_ticket_id"
        ),
        sa.CheckConstraint(
            "bushels > 0", name="ck_contract_application_bushels_positive"
        ),
    )
    op.create_index(
        "ix_contract_application_applied_on", "contract_application", ["applied_on"]
    )

    op.create_table(
        "settlement",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(24), nullable=False),
        sa.Column("settled_on", sa.Date(), nullable=False),
        sa.Column("party_code", sa.String(24), nullable=False),
        sa.Column("location_code", sa.String(12), nullable=False),
        sa.Column("gross", MONEY, nullable=False),
        sa.Column("deductions", MONEY, nullable=False),
        sa.Column("net", MONEY, nullable=False),
        sa.Column("paid_on", sa.Date()),
        sa.Column("cheque_number", sa.String(16)),
        sa.Column("voided", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("number", name="uq_settlement_number"),
    )
    op.create_index(
        "ix_settlement_party_code", "settlement", ["party_code", "settled_on"]
    )
    op.create_index("ix_settlement_settled_on", "settlement", ["settled_on"])

    op.create_table(
        "settlement_line",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "settlement_id",
            sa.Integer(),
            sa.ForeignKey("settlement.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("code", sa.String(24), nullable=False),
        sa.Column("description", sa.String(120), nullable=False),
        sa.Column("dollars", MONEY, nullable=False),
        sa.Column("quantity", BUSHELS),
        sa.Column("rate", PRICE),
        sa.UniqueConstraint(
            "settlement_id", "sequence", name="uq_settlement_line_settlement_id"
        ),
        sa.CheckConstraint(
            "dollars >= 0", name="ck_settlement_line_dollars_not_negative"
        ),
    )

    op.create_table(
        "settlement_ticket",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "settlement_id",
            sa.Integer(),
            sa.ForeignKey("settlement.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "ticket_id", sa.Integer(), sa.ForeignKey("scale_ticket.id"), nullable=False
        ),
        sa.UniqueConstraint(
            "settlement_id", "ticket_id", name="uq_settlement_ticket_settlement_id"
        ),
    )


def downgrade() -> None:
    op.drop_table("settlement_ticket")
    op.drop_table("settlement_line")
    op.drop_index("ix_settlement_settled_on", table_name="settlement")
    op.drop_index("ix_settlement_party_code", table_name="settlement")
    op.drop_table("settlement")
    op.drop_index(
        "ix_contract_application_applied_on", table_name="contract_application"
    )
    op.drop_table("contract_application")
    op.drop_index(
        "ix_purchase_contract_window_closes", table_name="purchase_contract"
    )
    op.drop_index("ix_purchase_contract_party_code", table_name="purchase_contract")
    op.drop_table("purchase_contract")
