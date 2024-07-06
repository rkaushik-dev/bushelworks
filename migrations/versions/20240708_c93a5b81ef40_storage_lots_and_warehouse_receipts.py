"""storage lots and warehouse receipts

Revision ID: c93a5b81ef40
Revises: b47d9e15c082
Created: 2024-07-08

The warehouse side. Withdrawals are their own table rather than a balance
column on the lot, because the licence requires the house to say when bushels
left and not only how many are left.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "c93a5b81ef40"
down_revision: str | None = "b47d9e15c082"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)
BUSHELS = sa.Numeric(14, 4)


def upgrade() -> None:
    op.create_table(
        "storage_lot",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("lot_id", sa.String(24), nullable=False),
        sa.Column("party_code", sa.String(24), nullable=False),
        sa.Column("commodity_code", sa.String(8), nullable=False),
        sa.Column("location_code", sa.String(12), nullable=False),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("stored_on", sa.Date(), nullable=False),
        sa.Column("ticket_id", sa.Integer(), sa.ForeignKey("scale_ticket.id")),
        sa.Column("closed_on", sa.Date()),
        sa.Column("billed_through", sa.Date()),
        sa.Column("accrued_to_date", MONEY, nullable=False, server_default="0"),
        sa.Column(
            "is_deferred_price", sa.Boolean(), nullable=False, server_default=sa.false()
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("lot_id", name="uq_storage_lot_lot_id"),
        sa.CheckConstraint("bushels > 0", name="ck_storage_lot_bushels_positive"),
    )
    op.create_index(
        "ix_storage_lot_party_code", "storage_lot", ["party_code", "commodity_code"]
    )
    op.create_index("ix_storage_lot_stored_on", "storage_lot", ["stored_on"])

    op.create_table(
        "storage_withdrawal",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "lot_row_id",
            sa.Integer(),
            sa.ForeignKey("storage_lot.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("withdrawn_on", sa.Date(), nullable=False),
        sa.Column("reason", sa.String(24), nullable=False, server_default="settled"),
        sa.Column("settlement_number", sa.String(24)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("bushels > 0", name="ck_storage_withdrawal_bushels_positive"),
    )
    op.create_index(
        "ix_storage_withdrawal_withdrawn_on", "storage_withdrawal", ["withdrawn_on"]
    )

    op.create_table(
        "warehouse_receipt",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("receipt_number", sa.String(24), nullable=False),
        sa.Column(
            "lot_row_id", sa.Integer(), sa.ForeignKey("storage_lot.id"), nullable=False
        ),
        sa.Column("party_code", sa.String(24), nullable=False),
        sa.Column("commodity_code", sa.String(8), nullable=False),
        sa.Column("location_code", sa.String(12), nullable=False),
        sa.Column("bushels", BUSHELS, nullable=False),
        sa.Column("grade", sa.String(8), nullable=False),
        sa.Column("issued_on", sa.Date(), nullable=False),
        sa.Column("cancelled_on", sa.Date()),
        sa.Column("lienholder", sa.String(60)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "receipt_number", name="uq_warehouse_receipt_receipt_number"
        ),
        sa.CheckConstraint("bushels > 0", name="ck_warehouse_receipt_bushels_positive"),
    )
    op.create_index(
        "ix_warehouse_receipt_party_code", "warehouse_receipt", ["party_code"]
    )


def downgrade() -> None:
    op.drop_index("ix_warehouse_receipt_party_code", table_name="warehouse_receipt")
    op.drop_table("warehouse_receipt")
    op.drop_index(
        "ix_storage_withdrawal_withdrawn_on", table_name="storage_withdrawal"
    )
    op.drop_table("storage_withdrawal")
    op.drop_index("ix_storage_lot_stored_on", table_name="storage_lot")
    op.drop_index("ix_storage_lot_party_code", table_name="storage_lot")
    op.drop_table("storage_lot")
