-- A customer's "this is cheaper elsewhere" request for a product, and the store's decision (see PriceMatchService).
CREATE TABLE IF NOT EXISTS `price_match_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `competitor_price` double NOT NULL,
  `our_price` double NOT NULL,
  `competitor_url` varchar(500) DEFAULT NULL,
  `status` varchar(16) NOT NULL,
  `admin_note` varchar(300) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_price_match_phno` (`customer_phno`),
  KEY `idx_price_match_status` (`status`)
);
