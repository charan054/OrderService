-- Gift wrap flag and card message chosen at checkout.
ALTER TABLE `cart` ADD COLUMN `gift_wrap` bit(1) NOT NULL DEFAULT b'0';
ALTER TABLE `cart` ADD COLUMN `gift_message` varchar(200) DEFAULT NULL;
