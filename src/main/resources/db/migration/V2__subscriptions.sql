-- Subscribe and save: the subscription itself, and which subscription (if any) an order is a repeat delivery of.
CREATE TABLE IF NOT EXISTS `subscription` (
  `consecutive_failures` int NOT NULL,
  `discount_percent` double NOT NULL,
  `interval_days` int NOT NULL,
  `orders_placed` int NOT NULL,
  `product_id` int NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_order_id` bigint DEFAULT NULL,
  `next_run_at` datetime(6) DEFAULT NULL,
  `shipping_address_id` bigint DEFAULT NULL,
  `status` varchar(12) NOT NULL,
  `last_error` varchar(200) DEFAULT NULL,
  `customer_name` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_subscription_phno` (`customer_phno`),
  KEY `idx_subscription_due` (`status`,`next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE `cart` ADD COLUMN `subscription_id` bigint DEFAULT NULL;
