-- Locks shared by every OrderService instance (per-order changes, scheduled jobs). See ClusterLocks.
CREATE TABLE IF NOT EXISTS `distributed_lock` (
  `lock_name` varchar(100) NOT NULL,
  `owner` varchar(64) DEFAULT NULL,
  `locked_until` datetime(6) NOT NULL,
  PRIMARY KEY (`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
