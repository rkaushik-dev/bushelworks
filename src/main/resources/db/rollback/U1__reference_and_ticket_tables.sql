-- Takes out revision 1: the reference and ticket tables, children first.

DROP TABLE ticket_split;
DROP TABLE ticket_sample;
DROP INDEX ix_scale_ticket_commodity_code;
DROP INDEX ix_scale_ticket_party_code;
DROP INDEX ix_scale_ticket_scale_date;
DROP TABLE scale_ticket;
DROP TABLE split_agreement;
DROP TABLE document_counter;
DROP TABLE commodity;
DROP TABLE location;
DROP INDEX ix_party_sort_name;
DROP TABLE party;
