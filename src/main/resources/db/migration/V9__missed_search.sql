-- Storefront searches that matched nothing, one counter row per (lower-cased) term.
CREATE TABLE IF NOT EXISTS `missed_search` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `term` varchar(60) NOT NULL,
  `times` int NOT NULL,
  `last_searched_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_missed_search_term` (`term`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
