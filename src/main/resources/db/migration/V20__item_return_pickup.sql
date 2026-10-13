-- Pickups booked for single items of a delivered order (returns.pickup).
CREATE TABLE IF NOT EXISTS `item_return_pickup` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `customer_phno` bigint NOT NULL,
  `product_id` int NOT NULL,
  `quantity` int NOT NULL,
  `reason` varchar(300) DEFAULT NULL,
  `refund_to` varchar(20) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_item_return_pickup_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
