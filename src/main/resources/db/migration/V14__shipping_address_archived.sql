-- A saved address that an order shipped to is never edited or deleted in place (that would rewrite old invoices and the GST
-- place of supply); it is archived instead and the edit becomes a new row. Archived rows are hidden from the customer.
ALTER TABLE `shipping_address` ADD COLUMN `archived` bit(1) NOT NULL DEFAULT b'0';
