-- Read-only wishlist share links: one random token per customer.
CREATE TABLE IF NOT EXISTS `wishlist_share` (
  `customer_phno` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `token` varchar(255) NOT NULL,
  PRIMARY KEY (`token`),
  UNIQUE KEY `uk_wishlist_share_phno` (`customer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
