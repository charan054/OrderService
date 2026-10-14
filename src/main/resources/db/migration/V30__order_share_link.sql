-- Revocable read-only links to one order's status (token is the whole credential; see OrderShareService).
CREATE TABLE IF NOT EXISTS `order_share_link` (
  `token` varchar(64) NOT NULL,
  `order_id` bigint NOT NULL,
  `customer_phno` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`token`),
  KEY `idx_order_share_link_order` (`order_id`),
  KEY `idx_order_share_link_phno` (`customer_phno`)
);
