-- Browsers a customer allowed notifications in (Web Push, see PushService).
CREATE TABLE IF NOT EXISTS `push_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_phno` bigint NOT NULL,
  `endpoint` varchar(500) NOT NULL,
  `p256dh` varchar(200) NOT NULL,
  `auth` varchar(100) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_push_subscription_endpoint` (`endpoint`),
  KEY `idx_push_subscription_phno` (`customer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
