-- Return pickup (returns.pickup): where the return is on its way back, and where the customer wants the refund.
ALTER TABLE `cart` ADD COLUMN `return_pickup_status` varchar(20) DEFAULT NULL, ADD COLUMN `return_refund_to` varchar(20) DEFAULT NULL;
