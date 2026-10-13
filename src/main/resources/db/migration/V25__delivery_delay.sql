-- New expected delivery date and the reason, when a shipped order is running late.
ALTER TABLE `cart` ADD COLUMN `expected_delivery_date` date DEFAULT NULL;
ALTER TABLE `cart` ADD COLUMN `delay_reason` varchar(200) DEFAULT NULL;
