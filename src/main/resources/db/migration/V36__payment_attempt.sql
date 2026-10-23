-- Every PhonePe charge made at checkout, recorded before the charge (PaymentAttemptService). A STARTED/CHARGED row that
-- never reaches COMPLETED/ROLLED_BACK is money that may have moved with no order - staff are alerted.
CREATE TABLE IF NOT EXISTS `payment_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_phno` bigint NOT NULL,
  `amount` double NOT NULL,
  `idempotency_key` varchar(64) DEFAULT NULL,
  `payment_transaction_id` bigint DEFAULT NULL,
  `order_id` bigint DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `resolution` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_payment_attempt_status` (`status`, `resolved_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
