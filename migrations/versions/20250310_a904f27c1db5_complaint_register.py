"""complaint register

Revision ID: a904f27c1db5
Revises: f61c8ad3e042
Created: 2025-03-10

Every column that names what the complaint is about is nullable, because which
one is filled in depends on who is complaining. The buyer names a sale, the
producer names a ticket, and the feedlot names neither.

``probe_id`` is a plain string and not a foreign key on purpose. Retained
samples are thrown out on a rota and a complaint filed against one should not
keep it on the shelf for ever.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "a904f27c1db5"
down_revision: str | None = "f61c8ad3e042"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)


def upgrade() -> None:
    op.create_table(
        "complaint",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("reference", sa.String(24), nullable=False),
        sa.Column("source", sa.String(12), nullable=False),
        sa.Column("subject", sa.String(200), nullable=False),
        sa.Column("raised_on", sa.Date(), nullable=False),
        sa.Column("delivered_on", sa.Date(), nullable=False),
        sa.Column("party_code", sa.String(24)),
        sa.Column("bin_id", sa.String(12)),
        sa.Column("ticket_number", sa.String(24)),
        sa.Column("sale_number", sa.String(24)),
        sa.Column("probe_id", sa.String(24)),
        sa.Column("outcome", sa.String(12), nullable=False, server_default="open"),
        sa.Column("credit", MONEY, nullable=False, server_default="0"),
        sa.Column("closed_on", sa.Date()),
        sa.Column("note", sa.String(400), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("reference", name="uq_complaint_reference"),
        sa.CheckConstraint("credit >= 0", name="credit_not_negative"),
        sa.CheckConstraint(
            "raised_on >= delivered_on", name="raised_after_delivery"
        ),
    )
    op.create_index("ix_complaint_bin_id", "complaint", ["bin_id", "raised_on"])
    op.create_index("ix_complaint_party_code", "complaint", ["party_code"])
    op.create_index("ix_complaint_outcome", "complaint", ["outcome", "raised_on"])

    op.create_table(
        "complaint_action",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("complaint_id", sa.Integer(), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("taken_on", sa.Date(), nullable=False),
        sa.Column("taken_by", sa.String(40), nullable=False),
        sa.Column("what", sa.String(200), nullable=False),
        sa.ForeignKeyConstraint(
            ["complaint_id"],
            ["complaint.id"],
            name="fk_complaint_action_complaint_id_complaint",
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint(
            "complaint_id", "sequence", name="uq_complaint_action_complaint_id"
        ),
    )


def downgrade() -> None:
    op.drop_table("complaint_action")
    op.drop_index("ix_complaint_outcome", table_name="complaint")
    op.drop_index("ix_complaint_party_code", table_name="complaint")
    op.drop_index("ix_complaint_bin_id", table_name="complaint")
    op.drop_table("complaint")
