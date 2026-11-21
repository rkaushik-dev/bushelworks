-- Takes out revision 7: the complaint register, actions first.

DROP TABLE complaint_action;
DROP INDEX ix_complaint_outcome;
DROP INDEX ix_complaint_party_code;
DROP INDEX ix_complaint_bin_id;
DROP TABLE complaint;
