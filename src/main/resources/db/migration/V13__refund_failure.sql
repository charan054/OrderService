-- A refund (or store credit return) the system could not complete while undoing a failed checkout; a person has to
-- settle it by hand and then mark it resolved.
CREATE TABLE IF NOT EXISTS `refund_failure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `customer_phno` bigint NOT NULL,
  `kind` varchar(20) DEFAULT NULL,
  `amount` double NOT NULL,
  `payment_transaction_id` bigint DEFAULT NULL,
  `reason` varchar(300) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `resolution` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_refund_failure_resolved` (`resolved_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
