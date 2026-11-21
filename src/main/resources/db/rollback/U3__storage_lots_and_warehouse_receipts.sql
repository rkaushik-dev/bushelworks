-- Takes out revision 3: storage lots, their withdrawals and the receipts written against them.

DROP INDEX ix_warehouse_receipt_party_code;
DROP TABLE warehouse_receipt;
DROP INDEX ix_storage_withdrawal_withdrawn_on;
DROP TABLE storage_withdrawal;
DROP INDEX ix_storage_lot_stored_on;
DROP INDEX ix_storage_lot_party_code;
DROP TABLE storage_lot;
