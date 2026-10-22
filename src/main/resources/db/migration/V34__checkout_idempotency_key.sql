-- A checkout retried with the same Idempotency-Key returns the order the first attempt created instead of placing a second.
-- Unique per customer; orders without a key (admin/API, subscriptions, everything older) leave it NULL, and MySQL allows many NULLs.
ALTER TABLE `cart` ADD COLUMN `idempotency_key` varchar(64) DEFAULT NULL;
ALTER TABLE `cart` ADD UNIQUE KEY `uk_cart_customer_idempotency` (`customer_phno`, `idempotency_key`);
