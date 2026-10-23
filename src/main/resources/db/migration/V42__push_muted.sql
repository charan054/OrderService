-- Which kinds of browser notification a customer switched off (comma separated; empty = all on).
ALTER TABLE `customer_account` ADD COLUMN `push_muted` varchar(60) DEFAULT NULL;
