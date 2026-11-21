-- Takes out revision 8: the reporting indexes. No result changes, only how long they take.

DROP INDEX ix_settlement_live_by_site;
DROP INDEX ix_scale_ticket_waiting;
DROP INDEX ix_scale_ticket_ticket_number;
DROP INDEX ix_purchase_contract_open_window;
DROP INDEX ix_contract_application_contract_live;
