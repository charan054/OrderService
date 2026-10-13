-- When staff marked a placed order as packed (it then drops out of the pick list).
ALTER TABLE `cart` ADD COLUMN `packed_at` datetime(6) DEFAULT NULL;
