-- Return approval (returns.require-approval): the customer's request and staff's decision, per order.
ALTER TABLE `cart` ADD COLUMN `return_request_status` varchar(20) DEFAULT NULL, ADD COLUMN `return_request_reason` varchar(300) DEFAULT NULL, ADD COLUMN `return_request_note` varchar(300) DEFAULT NULL;
