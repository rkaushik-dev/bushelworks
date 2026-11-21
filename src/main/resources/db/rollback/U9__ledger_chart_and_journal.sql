-- Takes out revision 9: the ledger, in the order the cascade needs, legs before their entries.

DROP INDEX ix_ledger_period_starts_on;
DROP TABLE ledger_period;
DROP INDEX ix_journal_leg_account_code;
DROP TABLE journal_leg;
DROP INDEX ix_journal_entry_source;
DROP INDEX ix_journal_entry_posted_on;
DROP TABLE journal_entry;
DROP INDEX ix_ledger_account_parent_code;
DROP TABLE ledger_account;
