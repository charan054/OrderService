-- Approving a price-match request can also start a timed sale on the product: remember its price and end.
ALTER TABLE `price_match_request`
  ADD COLUMN `sale_price` double DEFAULT NULL,
  ADD COLUMN `sale_ends_at` datetime(6) DEFAULT NULL;
