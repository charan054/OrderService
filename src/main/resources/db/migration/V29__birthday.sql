-- Optional birthday (day and month only) for the yearly birthday coupon, and the year it was last sent.
ALTER TABLE `customer_account` ADD COLUMN `birth_month` int DEFAULT NULL;
ALTER TABLE `customer_account` ADD COLUMN `birth_day` int DEFAULT NULL;
ALTER TABLE `customer_account` ADD COLUMN `birthday_coupon_year` int DEFAULT NULL;
