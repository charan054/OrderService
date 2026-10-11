-- "Tell me when you stock this": a customer's saved search term, emailed once when a matching product is in stock.
CREATE TABLE IF NOT EXISTS `search_watch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_phno` bigint NOT NULL,
  `term` varchar(60) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `notified_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_search_watch_customer_term` (`customer_phno`,`term`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
