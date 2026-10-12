-- A unit held for the waitlisted customer who was just told a product is back in stock (see StockAlertService).
ALTER TABLE `stock_waitlist` ADD COLUMN `held_until` datetime(6) DEFAULT NULL;
