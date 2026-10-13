-- Customer requests to swap delivered units for another product (no money moves).
CREATE TABLE IF NOT EXISTS `exchange_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `customer_phno` bigint NOT NULL,
  `product_id` int NOT NULL,
  `quantity` int NOT NULL,
  `replacement_product_id` int NOT NULL,
  `reason` varchar(300) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_exchange_request_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
