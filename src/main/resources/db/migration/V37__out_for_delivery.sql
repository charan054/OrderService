-- When a shipped parcel was marked "out for delivery" today (customer gets an email).
ALTER TABLE `cart` ADD COLUMN `out_for_delivery_at` datetime(6) DEFAULT NULL;
