-- Takes out revision 2: the contract and settlement tables, children first.

DROP TABLE settlement_ticket;
DROP TABLE settlement_line;
DROP INDEX ix_settlement_settled_on;
DROP INDEX ix_settlement_party_code;
DROP TABLE settlement;
DROP INDEX ix_contract_application_applied_on;
DROP TABLE contract_application;
DROP INDEX ix_purchase_contract_window_closes;
DROP INDEX ix_purchase_contract_party_code;
DROP TABLE purchase_contract;
