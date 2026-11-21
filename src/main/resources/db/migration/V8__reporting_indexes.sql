-- Reporting indexes, one per query that had started to scan.
--
-- The position run groups live applications by contract, the settlement run asks what is waiting at
-- one site, the patronage table walks a crop year of live settlements, and a complaint arrives with a
-- ticket number and no site.
--
-- None of these change a result. Every one of them was found by turning on statement logging on the
-- office box for a morning and reading what came back, which is a cheaper way to find a missing index
-- than waiting for somebody to complain that the screen is slow.

CREATE INDEX ix_contract_application_contract_live ON contract_application (contract_id, reversed_on);
CREATE INDEX ix_purchase_contract_open_window ON purchase_contract (commodity_code, status, window_closes);
CREATE INDEX ix_scale_ticket_ticket_number ON scale_ticket (ticket_number);
CREATE INDEX ix_scale_ticket_waiting ON scale_ticket (location_code, settled, voided, scale_date);
CREATE INDEX ix_settlement_live_by_site ON settlement (location_code, voided, settled_on);
