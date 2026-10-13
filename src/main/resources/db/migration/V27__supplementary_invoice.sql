-- credit_note rows can also be supplementary invoices (extra value supplied after the invoice, e.g. a dearer exchange).
ALTER TABLE `credit_note` ADD COLUMN `kind` varchar(24) NOT NULL DEFAULT 'CREDIT_NOTE';
