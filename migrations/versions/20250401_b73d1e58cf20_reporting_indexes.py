"""reporting indexes

Revision ID: b73d1e58cf20
Revises: a904f27c1db5
Created: 2025-04-01

Four indexes, one per query that had started to scan.

The position run groups live applications by contract, the settlement run asks
what is waiting at one site, the patronage table walks a crop year of live
settlements, and a complaint arrives with a ticket number and no site.

None of these change a result. Every one of them was found by turning on
statement logging on the office box for a morning and reading what came back,
which is a cheaper way to find a missing index than waiting for somebody to
complain that the screen is slow.
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "b73d1e58cf20"
down_revision: str | None = "a904f27c1db5"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        "ix_contract_application_contract_live",
        "contract_application",
        ["contract_id", "reversed_on"],
    )
    op.create_index(
        "ix_purchase_contract_open_window",
        "purchase_contract",
        ["commodity_code", "status", "window_closes"],
    )
    op.create_index(
        "ix_scale_ticket_ticket_number",
        "scale_ticket",
        ["ticket_number"],
    )
    op.create_index(
        "ix_scale_ticket_waiting",
        "scale_ticket",
        ["location_code", "settled", "voided", "scale_date"],
    )
    op.create_index(
        "ix_settlement_live_by_site",
        "settlement",
        ["location_code", "voided", "settled_on"],
    )


def downgrade() -> None:
    op.drop_index("ix_settlement_live_by_site", table_name="settlement")
    op.drop_index("ix_scale_ticket_waiting", table_name="scale_ticket")
    op.drop_index("ix_scale_ticket_ticket_number", table_name="scale_ticket")
    op.drop_index("ix_purchase_contract_open_window", table_name="purchase_contract")
    op.drop_index(
        "ix_contract_application_contract_live", table_name="contract_application"
    )
