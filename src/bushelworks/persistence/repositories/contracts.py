"""The contract repository.

Contracts are replaced whole rather than patched. A contract is a document and
the office amends it by writing a new one, so ``replace`` copies every column
except the identity and the timestamps.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ...domain.contracts import ContractStatus, PurchaseContract
from ...errors import ConflictError, LookupFailure
from ..base import as_decimal
from ..mappers import contract_from_row, contract_to_row
from ..models import ApplicationRow, ContractRow


class ContractNotFound(LookupFailure):
    code = "contract_not_found"


class ContractRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def row(self, number: str) -> ContractRow:
        found = self.session.scalar(
            select(ContractRow).where(ContractRow.number == number)
        )
        if found is None:
            raise ContractNotFound(f"no contract {number}")
        return found

    def get(self, number: str) -> PurchaseContract:
        return contract_from_row(self.row(number))

    def add(self, contract: PurchaseContract) -> ContractRow:
        existing = self.session.scalar(
            select(ContractRow).where(ContractRow.number == contract.number)
        )
        if existing is not None:
            raise ConflictError(f"contract {contract.number} already exists")
        row = contract_to_row(contract)
        self.session.add(row)
        self.session.flush()
        return row

    def replace(self, contract: PurchaseContract) -> ContractRow:
        """Write an amended contract back over its row.

        Contracts are amended rather than voided, because a hedge to arrive that
        has had its basis set is the same contract, and the futures position is
        booked against its number.
        """
        row = self.row(contract.number)
        updated = contract_to_row(contract)
        for column in [c.name for c in ContractRow.__table__.columns]:
            # The bookkeeping columns belong to the row, not to the contract.
            # Copying them across would blank updated_at, which is set by the
            # ORM on flush and is null on an object that has never been added.
            if column in ("id", "created_at", "updated_at"):
                continue
            setattr(row, column, getattr(updated, column))
        self.session.flush()
        return row

    def open_for(
        self, party_code: str, commodity_code: str, day: date
    ) -> list[PurchaseContract]:
        rows = self.session.scalars(
            select(ContractRow)
            .where(
                ContractRow.party_code == party_code,
                ContractRow.commodity_code == commodity_code,
                ContractRow.status == ContractStatus.OPEN.value,
                ContractRow.window_opens <= day,
                ContractRow.window_closes >= day,
            )
            .order_by(ContractRow.window_closes, ContractRow.number)
        )
        return [contract_from_row(row) for row in rows]

    def applied_bushels(self, number: str) -> Decimal:
        row = self.row(number)
        total = self.session.scalar(
            select(func.sum(ApplicationRow.bushels)).where(
                ApplicationRow.contract_id == row.id,
                ApplicationRow.reversed_on.is_(None),
            )
        )
        return as_decimal(total)

    def close(self, number: str) -> ContractRow:
        row = self.row(number)
        if row.status != ContractStatus.OPEN.value:
            raise ConflictError(f"contract {number} is {row.status}, not open")
        row.status = ContractStatus.FILLED.value
        self.session.flush()
        return row
