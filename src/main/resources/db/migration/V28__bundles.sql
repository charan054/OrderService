-- Fixed-price product bundles. A bundle is a set of products (with quantities) sold for one price; at checkout every
-- complete set in the order is discounted to that price (cart.bundle_discount records how much came off).
CREATE TABLE IF NOT EXISTS `bundle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(120) NOT NULL,
  `bundle_price` double NOT NULL,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
);
CREATE TABLE IF NOT EXISTS `bundle_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bundle_id` bigint NOT NULL,
  `product_id` int NOT NULL,
  `quantity` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_bundle_item_bundle` (`bundle_id`)
);
ALTER TABLE `cart` ADD COLUMN `bundle_discount` double DEFAULT NULL;
