-- Optional per-pincode delivery fee (null = the shop-wide shipping.fee).
ALTER TABLE `serviceable_pincode` ADD COLUMN `delivery_fee` double DEFAULT NULL;
