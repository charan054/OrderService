-- Price difference of an exchange (replacement minus original, per the units swapped): positive = customer pays from
-- store credit when approved, negative = refunded to store credit when the old item is back.
ALTER TABLE `exchange_request` ADD COLUMN `price_difference` double NOT NULL DEFAULT 0;
