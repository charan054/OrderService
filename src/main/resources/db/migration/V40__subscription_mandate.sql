-- A subscription can pay each delivery through a PhonePe mandate instead of cash on delivery.
ALTER TABLE `subscription` ADD COLUMN `mandate_id` bigint DEFAULT NULL;
