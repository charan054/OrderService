-- Remembers the payment key of a mandate charge whose outcome is unknown (timeout, bank down), so the retry asks PhonePe about
-- that same payment instead of charging a second time.
ALTER TABLE `subscription` ADD COLUMN `pending_charge_key` varchar(80) DEFAULT NULL;
