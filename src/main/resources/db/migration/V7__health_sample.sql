-- One row per service per health probe, for the admin "uptime in the last 24 hours" strip. Old rows are purged by
-- HealthHistoryService (health.history.retention-days, default 7).
CREATE TABLE IF NOT EXISTS `health_sample` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `response_millis` bigint NOT NULL,
  `checked_at` datetime(6) NOT NULL,
  `detail` varchar(200) DEFAULT NULL,
  `service_name` varchar(40) NOT NULL,
  `status` varchar(8) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_health_sample_checked_at` (`checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
