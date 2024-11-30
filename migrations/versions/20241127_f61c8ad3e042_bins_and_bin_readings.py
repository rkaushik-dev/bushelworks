"""bins and bin readings

Revision ID: f61c8ad3e042
Revises: e2b7c40d915a
Created: 2024-11-27

Readings are their own table. The aeration log is a compliance record and the
state asks what the temperature was doing in the week before a load went out,
which a single last reading column cannot answer.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "f61c8ad3e042"
down_revision: str | None = "e2b7c40d915a"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

BUSHELS = sa.Numeric(14, 4)
PERCENT = sa.Numeric(7, 4)


def upgrade() -> None:
    op.create_table(
        "bin",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("bin_id", sa.String(12), nullable=False),
        sa.Column("location_code", sa.String(12), nullable=False),
        sa.Column("capacity_bushels", BUSHELS, nullable=False),
        sa.Column("bushels", BUSHELS, nullable=False, server_default="0"),
        sa.Column("commodity_code", sa.String(8)),
        sa.Column("moisture", PERCENT),
        sa.Column("test_weight", PERCENT),
        sa.Column("damage", PERCENT),
        sa.Column("is_aerated", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column(
            "out_of_service", sa.Boolean(), nullable=False, server_default=sa.false()
        ),
        sa.Column("out_of_service_reason", sa.String(120)),
        sa.Column("last_turned_on", sa.Date()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("location_code", "bin_id", name="uq_bin_location_code"),
        sa.CheckConstraint("capacity_bushels > 0", name="ck_bin_capacity_positive"),
        sa.CheckConstraint("bushels >= 0", name="ck_bin_bushels_not_negative"),
        sa.CheckConstraint("bushels <= capacity_bushels", name="ck_bin_bushels_fit"),
    )
    op.create_index("ix_bin_commodity_code", "bin", ["commodity_code"])

    op.create_table(
        "bin_reading",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "bin_row_id",
            sa.Integer(),
            sa.ForeignKey("bin.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("taken_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("cable", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("temperature_c", PERCENT, nullable=False),
        sa.Column("moisture", PERCENT),
        sa.Column(
            "fan_running", sa.Boolean(), nullable=False, server_default=sa.false()
        ),
        sa.Column("note", sa.String(120)),
        sa.CheckConstraint(
            "temperature_c > -60 AND temperature_c < 90",
            name="ck_bin_reading_temperature_range",
        ),
    )
    op.create_index("ix_bin_reading_taken_at", "bin_reading", ["taken_at"])


def downgrade() -> None:
    op.drop_index("ix_bin_reading_taken_at", table_name="bin_reading")
    op.drop_table("bin_reading")
    op.drop_index("ix_bin_commodity_code", table_name="bin")
    op.drop_table("bin")
