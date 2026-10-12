-- When a cash-on-delivery order was marked collected (for the cash collection report).
ALTER TABLE `cart` ADD COLUMN `paid_at` datetime(6) DEFAULT NULL;
