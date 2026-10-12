-- Which purchase a "time to reorder?" email was sent about, so each repeat purchase is nudged once.
CREATE TABLE IF NOT EXISTS `reorder_reminder` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_phno` bigint NOT NULL,
  `product_id` int NOT NULL,
  `last_purchase_at` datetime(6) DEFAULT NULL,
  `reminded_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reorder_reminder_customer_product` (`customer_phno`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
