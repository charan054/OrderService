-- How many times a wishlist share link has been opened.
ALTER TABLE `wishlist_share` ADD COLUMN `view_count` bigint NOT NULL DEFAULT 0;
