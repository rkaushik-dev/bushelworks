"""audit trail

Revision ID: d15e0a73b6c9
Revises: c93a5b81ef40
Created: 2024-07-24

Append only. No foreign keys on purpose: an audit row has to survive the
document it describes being voided and reissued, and a key would either block
that or cascade the evidence away with it.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "d15e0a73b6c9"
down_revision: str | None = "c93a5b81ef40"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

MONEY = sa.Numeric(14, 2)


def upgrade() -> None:
    op.create_table(
        "audit_entry",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("effective_on", sa.Date()),
        sa.Column("actor", sa.String(60), nullable=False, server_default="system"),
        sa.Column("action", sa.String(40), nullable=False),
        sa.Column("document_kind", sa.String(24), nullable=False),
        sa.Column("document_number", sa.String(24), nullable=False),
        sa.Column("party_code", sa.String(24)),
        sa.Column("location_code", sa.String(12)),
        sa.Column("amount", MONEY),
        sa.Column("detail", sa.String(400)),
        sa.Column("request_id", sa.String(32)),
        sa.CheckConstraint("length(action) > 0", name="ck_audit_entry_action_not_blank"),
    )
    op.create_index("ix_audit_entry_recorded_at", "audit_entry", ["recorded_at"])
    op.create_index(
        "ix_audit_entry_document", "audit_entry", ["document_kind", "document_number"]
    )
    op.create_index("ix_audit_entry_actor", "audit_entry", ["actor"])


def downgrade() -> None:
    op.drop_index("ix_audit_entry_actor", table_name="audit_entry")
    op.drop_index("ix_audit_entry_document", table_name="audit_entry")
    op.drop_index("ix_audit_entry_recorded_at", table_name="audit_entry")
    op.drop_table("audit_entry")
