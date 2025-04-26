"""ledger chart and journal

Revision ID: c5f0a92b7e14
Revises: b73d1e58cf20
Created: 2025-04-26

The journal has no status column and no way to update a row, which is the shape
the tables are meant to have rather than something left to add later. A wrong
entry is corrected by posting its reverse, and ``reverses`` records which one it
was correcting.

``parent_code`` and ``account_code`` are plain strings, not foreign keys into
``ledger_account``. The chart is filed once per house and read as a whole, and a
foreign key there would mean the account table has to be populated before a
migration test can post anything into the journal.

The downgrade drops the tables in the order the cascade needs, legs first.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "c5f0a92b7e14"
down_revision: str | None = "b73d1e58cf20"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)


def upgrade() -> None:
    op.create_table(
        "ledger_account",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("code", sa.String(12), nullable=False),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("type", sa.String(12), nullable=False),
        sa.Column("parent_code", sa.String(12)),
        sa.Column("closes_to", sa.String(12)),
        sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("code", name="uq_ledger_account_code"),
        sa.CheckConstraint(
            "type in ('asset', 'liability', 'equity', 'revenue', 'expense')",
            name="type_is_known",
        ),
        sa.CheckConstraint("code <> parent_code", name="not_its_own_parent"),
    )
    op.create_index(
        "ix_ledger_account_parent_code", "ledger_account", ["parent_code"]
    )

    op.create_table(
        "journal_entry",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("number", sa.String(24), nullable=False),
        sa.Column("posted_on", sa.Date(), nullable=False),
        sa.Column("memo", sa.String(200), nullable=False, server_default=""),
        sa.Column("source", sa.String(24), nullable=False, server_default=""),
        sa.Column(
            "source_reference", sa.String(48), nullable=False, server_default=""
        ),
        sa.Column("reverses", sa.String(24)),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("number", name="uq_journal_entry_number"),
    )
    op.create_index(
        "ix_journal_entry_posted_on", "journal_entry", ["posted_on", "number"]
    )
    op.create_index(
        "ix_journal_entry_source", "journal_entry", ["source", "source_reference"]
    )

    op.create_table(
        "journal_leg",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("entry_id", sa.Integer(), nullable=False),
        sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("account_code", sa.String(12), nullable=False),
        sa.Column("side", sa.String(6), nullable=False),
        sa.Column("dollars", MONEY, nullable=False),
        sa.Column("memo", sa.String(120), nullable=False, server_default=""),
        sa.ForeignKeyConstraint(
            ["entry_id"],
            ["journal_entry.id"],
            name="fk_journal_leg_entry_id_journal_entry",
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("entry_id", "sequence", name="uq_journal_leg_entry_id"),
        sa.CheckConstraint("dollars > 0", name="leg_is_positive"),
        sa.CheckConstraint("side in ('debit', 'credit')", name="side_is_known"),
    )
    op.create_index("ix_journal_leg_account_code", "journal_leg", ["account_code"])

    op.create_table(
        "ledger_period",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("code", sa.String(7), nullable=False),
        sa.Column("starts_on", sa.Date(), nullable=False),
        sa.Column("ends_on", sa.Date(), nullable=False),
        sa.Column("closed", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("closed_on", sa.Date()),
        sa.Column("closed_by", sa.String(48), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("code", name="uq_ledger_period_code"),
        sa.CheckConstraint("ends_on >= starts_on", name="period_runs_forwards"),
    )
    op.create_index("ix_ledger_period_starts_on", "ledger_period", ["starts_on"])


def downgrade() -> None:
    op.drop_index("ix_ledger_period_starts_on", table_name="ledger_period")
    op.drop_table("ledger_period")
    op.drop_index("ix_journal_leg_account_code", table_name="journal_leg")
    op.drop_table("journal_leg")
    op.drop_index("ix_journal_entry_source", table_name="journal_entry")
    op.drop_index("ix_journal_entry_posted_on", table_name="journal_entry")
    op.drop_table("journal_entry")
    op.drop_index("ix_ledger_account_parent_code", table_name="ledger_account")
    op.drop_table("ledger_account")
