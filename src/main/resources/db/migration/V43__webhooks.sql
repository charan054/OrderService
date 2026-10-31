-- Outbound webhooks: URLs an admin registered to be told about order status changes (see WebhookService).
CREATE TABLE IF NOT EXISTS `webhook_endpoint` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `url` varchar(500) NOT NULL,
  `secret` varchar(80) NOT NULL,
  `active` bit(1) NOT NULL,
  `failed_streak` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One row per (status change, endpoint): written when the change happens, sent later with retries.
-- status is plain text (not an enum) so a new order status never needs a schema change.
CREATE TABLE IF NOT EXISTS `webhook_delivery` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `endpoint_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `status` varchar(30) NOT NULL,
  `occurred_at` datetime(6) NOT NULL,
  `state` varchar(12) NOT NULL,
  `attempts` int NOT NULL,
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `last_error` varchar(300) DEFAULT NULL,
  `delivered_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_webhook_delivery_due` (`state`, `next_attempt_at`),
  KEY `idx_webhook_delivery_endpoint` (`endpoint_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
