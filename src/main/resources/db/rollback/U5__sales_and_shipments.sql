-- Takes out revision 5: sales and their shipments.

DROP INDEX ix_shipment_carrier_id;
DROP INDEX ix_shipment_shipped_on;
DROP TABLE shipment;
DROP INDEX ix_sale_contract_commodity_code;
DROP INDEX ix_sale_contract_buyer;
DROP TABLE sale_contract;
