-- Delivery fee charged on an order (already part of total_price). 0 for every order placed before fees existed.
ALTER TABLE `cart` ADD COLUMN `delivery_fee` double NOT NULL DEFAULT 0;
