-- Quantity price breaks ("buy 3 or more, save 10%"). At checkout the saving is taken like a bundle saving;
-- cart.quantity_discount records how much of the order's discount came from them.
CREATE TABLE IF NOT EXISTS `quantity_break` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` int NOT NULL,
  `min_quantity` int NOT NULL,
  `percent_off` int NOT NULL,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_quantity_break_product` (`product_id`)
);
ALTER TABLE `cart` ADD COLUMN `quantity_discount` double DEFAULT NULL;
