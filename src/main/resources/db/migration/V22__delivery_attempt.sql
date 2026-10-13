-- Failed delivery attempts on shipped orders.
CREATE TABLE IF NOT EXISTS `delivery_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `attempt_no` int NOT NULL,
  `reason` varchar(30) DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_delivery_attempt_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
