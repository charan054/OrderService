-- Baseline of the OrderService schema as the JPA entities define it on 2026-10-08.
-- Every statement is IF NOT EXISTS, so it is a no-op on a database that Hibernate (ddl-auto=update)
-- already built, and builds the whole schema on a brand-new one. All services share one MySQL
-- schema, so each keeps its own history table (see spring.flyway.table).

CREATE TABLE IF NOT EXISTS `admin_account` (
  `active` bit(1) NOT NULL,
  `failed_logins` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_failed_login_at` datetime(6) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `created_by` varchar(32) DEFAULT NULL,
  `username` varchar(32) NOT NULL,
  `password_hash` varchar(100) NOT NULL,
  `role` varchar(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKexwatsf3dxpn2oaw0dq89f5bi` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `admin_login_event` (
  `success` bit(1) NOT NULL,
  `at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `result` varchar(20) DEFAULT NULL,
  `username` varchar(32) DEFAULT NULL,
  `remote_addr` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDXhdh3kegti26urh0gpkxipnkra` (`at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `admin_session` (
  `admin_id` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKebox0rrpb1mv8ve5looe3siyc` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `audit_log` (
  `status` int NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `timestamp` datetime(6) DEFAULT NULL,
  `method` varchar(10) DEFAULT NULL,
  `actor` varchar(32) DEFAULT NULL,
  `remote_addr` varchar(64) DEFAULT NULL,
  `path` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDXjb2hs4ee3qd3p053xmxk45794` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `cart` (
  `discount_amount` double NOT NULL,
  `paid` bit(1) NOT NULL,
  `points_redeemed` int DEFAULT NULL,
  `refunded_amount` double NOT NULL,
  `store_credit_refunded` double NOT NULL,
  `store_credit_used` double DEFAULT NULL,
  `total_price` double NOT NULL,
  `customer_phno` bigint NOT NULL,
  `invoice_date` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `payment_deadline` datetime(6) DEFAULT NULL,
  `payment_transaction_id` bigint DEFAULT NULL,
  `shipping_address_id` bigint DEFAULT NULL,
  `delivery_slot` varchar(20) DEFAULT NULL,
  `invoice_number` varchar(30) DEFAULT NULL,
  `cancel_reason` varchar(40) DEFAULT NULL,
  `carrier` varchar(60) DEFAULT NULL,
  `tracking_number` varchar(60) DEFAULT NULL,
  `cancel_note` varchar(200) DEFAULT NULL,
  `delivery_note` varchar(200) DEFAULT NULL,
  `coupon_code` varchar(255) DEFAULT NULL,
  `customer_name` varchar(255) DEFAULT NULL,
  `return_reason` varchar(255) DEFAULT NULL,
  `upi_id` varchar(255) DEFAULT NULL,
  `payment_method` varchar(20) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `UKetavt513efpaa46y820t9m2n2` (`invoice_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `cod_override` (
  `phno` bigint NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `note` varchar(200) DEFAULT NULL,
  `mode` varchar(10) NOT NULL,
  PRIMARY KEY (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `coupon` (
  `active` bit(1) NOT NULL,
  `discount_percent` double NOT NULL,
  `max_redemptions` int DEFAULT NULL,
  `per_customer_limit` int DEFAULT NULL,
  `redemption_count` int NOT NULL,
  `unlisted` bit(1) NOT NULL,
  `expiry_date` datetime(6) DEFAULT NULL,
  `code` varchar(255) NOT NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `coupon_redemption` (
  `count` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `coupon_code` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK5sahyumnf3pms57fy22l2i9ta` (`coupon_code`,`customer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `credit_note` (
  `cgst` double NOT NULL,
  `igst` double NOT NULL,
  `inter_state` bit(1) NOT NULL,
  `sgst` double NOT NULL,
  `taxable_value` double NOT NULL,
  `total` double NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `issued_at` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  `reason` varchar(20) DEFAULT NULL,
  `invoice_number` varchar(40) DEFAULT NULL,
  `number` varchar(40) NOT NULL,
  `place_of_supply` varchar(60) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK2o6k0n7okfvrk1cwco2ngvxgg` (`number`),
  KEY `IDXj74txicbjh9rsjwkt8fkmdu7w` (`order_id`),
  KEY `IDXnes62atyh5giry7nkvxkintxd` (`issued_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `credit_note_line` (
  `cgst` double NOT NULL,
  `gst_rate` double NOT NULL,
  `igst` double NOT NULL,
  `product_id` int NOT NULL,
  `quantity` int NOT NULL,
  `sgst` double NOT NULL,
  `taxable_value` double NOT NULL,
  `total` double NOT NULL,
  `credit_note_id` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `hsn_code` varchar(16) DEFAULT NULL,
  `product_name` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK7orbdur3svcq4ncuaphr3nyvf` (`credit_note_id`),
  CONSTRAINT `FK7orbdur3svcq4ncuaphr3nyvf` FOREIGN KEY (`credit_note_id`) REFERENCES `credit_note` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `credit_note_sequence` (
  `fiscal_year` varchar(7) NOT NULL,
  `last_number` bigint NOT NULL,
  PRIMARY KEY (`fiscal_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `customer_account` (
  `marketing_opt_out` bit(1) NOT NULL,
  `phno` bigint NOT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  `referral_code` varchar(16) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`phno`),
  UNIQUE KEY `UKc9ra54ung7not8kvu3ueo6blt` (`referral_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `customer_login_code` (
  `attempts` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint DEFAULT NULL,
  `code_hash` varchar(64) NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmrlbwgg44f8x8x0xke40bm1h7` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `customer_session` (
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phno` bigint NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKkh9cfjhj458049pl1r5s3xwi3` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `gift_card` (
  `amount` double NOT NULL,
  `last4` varchar(4) DEFAULT NULL,
  `voided` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `redeemed_at` datetime(6) DEFAULT NULL,
  `redeemed_by` bigint DEFAULT NULL,
  `voided_at` datetime(6) DEFAULT NULL,
  `created_by` varchar(32) DEFAULT NULL,
  `code_hash` varchar(64) NOT NULL,
  `note` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9w5g5e4f90bd29cnlcu9ypbn8` (`code_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `invoice_sequence` (
  `fiscal_year` varchar(7) NOT NULL,
  `last_number` bigint NOT NULL,
  PRIMARY KEY (`fiscal_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `loyalty_account` (
  `points_balance` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `expiry_warned_at` datetime(6) DEFAULT NULL,
  `last_activity_at` datetime(6) DEFAULT NULL,
  `lifetime_points_earned` bigint NOT NULL,
  PRIMARY KEY (`customer_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `loyalty_transaction` (
  `points` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `timestamp` datetime(6) DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `type` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `notification_log` (
  `emailed` bit(1) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `message` varchar(255) DEFAULT NULL,
  `event_type` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `order_feedback` (
  `delivery_rating` int DEFAULT NULL,
  `rating` int NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `comment` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1innxlfe4w8vu0yyviwsikroh` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `order_item` (
  `cancelled_quantity` int NOT NULL,
  `gst_rate` double DEFAULT NULL,
  `product_id` int NOT NULL,
  `product_quantity` int NOT NULL,
  `returned_quantity` int NOT NULL,
  `unit_price` double DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `order_items_order_id` bigint DEFAULT NULL,
  `return_reason` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKpr3p4qmg0or7hfem9d67fr88l` (`order_items_order_id`),
  CONSTRAINT `FKpr3p4qmg0or7hfem9d67fr88l` FOREIGN KEY (`order_items_order_id`) REFERENCES `cart` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `order_note` (
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `note` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `IDX143r97aed2fxapw4k83ah6lc0` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `product_question` (
  `product_id` int NOT NULL,
  `answered_at` datetime(6) DEFAULT NULL,
  `asker_phno` bigint NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `question` varchar(500) DEFAULT NULL,
  `answer` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `referral` (
  `bonus_points` int NOT NULL,
  `rewarded` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `referee_phno` bigint NOT NULL,
  `referrer_phno` bigint NOT NULL,
  `reward_order_id` bigint DEFAULT NULL,
  `rewarded_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK14hwkf0ge8692myjk9hdmhxhb` (`referee_phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `saved_cart` (
  `phno` bigint NOT NULL,
  `reminder_sent_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `saved_cart_line` (
  `product_id` int DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `phno` bigint NOT NULL,
  KEY `FK1l0gb872je7rj3m1u6d6r821n` (`phno`),
  CONSTRAINT `FK1l0gb872je7rj3m1u6d6r821n` FOREIGN KEY (`phno`) REFERENCES `saved_cart` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `serviceable_pincode` (
  `delivery_days` int NOT NULL,
  `pincode` varchar(255) NOT NULL,
  PRIMARY KEY (`pincode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `shipping_address` (
  `is_default` bit(1) NOT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `city` varchar(255) DEFAULT NULL,
  `label` varchar(255) DEFAULT NULL,
  `line1` varchar(255) DEFAULT NULL,
  `line2` varchar(255) DEFAULT NULL,
  `pincode` varchar(255) DEFAULT NULL,
  `state` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `stock_waitlist` (
  `product_id` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `notified_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKrybamgcx4ome00f3eb17adr01` (`customer_phno`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `store_credit_account` (
  `balance` double NOT NULL,
  `phno` bigint NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `store_credit_transaction` (
  `amount` double NOT NULL,
  `balance_after` double NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `phno` bigint NOT NULL,
  `note` varchar(200) DEFAULT NULL,
  `type` varchar(12) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_store_credit_tx_phno` (`phno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `support_message` (
  `created_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ticket_id` bigint NOT NULL,
  `body` varchar(1000) DEFAULT NULL,
  `author` varchar(10) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_support_message_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `support_ticket` (
  `product_id` int DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `photo_url` varchar(500) DEFAULT NULL,
  `resolution_note` varchar(500) DEFAULT NULL,
  `category` varchar(20) NOT NULL,
  `status` varchar(12) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_support_ticket_phno` (`customer_phno`),
  KEY `idx_support_ticket_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `tracking_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint DEFAULT NULL,
  `timestamp` datetime(6) DEFAULT NULL,
  `status` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `wishlist` (
  `last_alerted_price` double DEFAULT NULL,
  `price_when_added` double DEFAULT NULL,
  `product_id` int NOT NULL,
  `customer_phno` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKbg37xr7lty2kt86jy9q45t687` (`customer_phno`,`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
