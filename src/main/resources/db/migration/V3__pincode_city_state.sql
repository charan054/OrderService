-- Optional city/state per serviceable pincode, used to autofill the storefront address form.
ALTER TABLE `serviceable_pincode` ADD COLUMN `city` varchar(255) DEFAULT NULL;
ALTER TABLE `serviceable_pincode` ADD COLUMN `state` varchar(255) DEFAULT NULL;
