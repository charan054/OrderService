-- Help / FAQ entries shown on the storefront, editable by admins. A few starter answers are seeded.
CREATE TABLE IF NOT EXISTS `faq` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sort_order` int NOT NULL,
  `question` varchar(200) NOT NULL,
  `answer` varchar(2000) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `faq` (`sort_order`, `question`, `answer`, `updated_at`) VALUES
(10, 'How do I track my order?', 'Open the My account tab to see every order with its current status. You can also use Track an order instead on the sign-in screen with your phone number and order number.', UTC_TIMESTAMP(6)),
(20, 'Can I cancel an order?', 'Yes, while it is still in the Placed state. Use the Cancel button on the order in My account. Once an order has shipped it can no longer be cancelled, but you can return it after delivery.', UTC_TIMESTAMP(6)),
(30, 'How do returns and refunds work?', 'After an order is delivered you have 7 days to request a return from My account. Paid orders are refunded to the account they were paid from; cash orders are refunded as store credit.', UTC_TIMESTAMP(6)),
(40, 'What payment options are available?', 'Pay online with PhonePe, or choose cash on delivery. Cash on delivery can be switched off for a phone number after repeated unsuccessful cash orders.', UTC_TIMESTAMP(6)),
(50, 'How do loyalty points work?', 'You earn points when an order is delivered, and can redeem them at checkout. Points expire after a long period with no activity on your account.', UTC_TIMESTAMP(6)),
(60, 'I have a problem with my order. What now?', 'Use Report a problem on the order in My account and tell us what happened. We reply by email and in My account.', UTC_TIMESTAMP(6));
