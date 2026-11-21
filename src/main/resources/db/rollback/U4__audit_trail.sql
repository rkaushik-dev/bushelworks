-- Takes out revision 4: the audit trail.

DROP INDEX ix_audit_entry_actor;
DROP INDEX ix_audit_entry_document;
DROP INDEX ix_audit_entry_recorded_at;
DROP TABLE audit_entry;
