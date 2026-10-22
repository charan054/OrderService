package com.example.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * One row per named lock shared by every OrderService instance on the same database (see ClusterLocks): "order-42" while
 * one instance changes that order, "job-digest" while one instance runs the daily digest. A row whose lockedUntil has
 * passed is free - that is how a lock held by an instance that died is released.
 */
@Data
@Entity
@Table(name = "distributed_lock")
public class DistributedLock {
    @Id
    @Column(name = "lock_name", length = 100)
    private String lockName;
    @Column(length = 64)
    private String owner;
    @Column(nullable = false)
    private Instant lockedUntil;
}
