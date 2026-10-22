package com.example.orderservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Named locks that hold across several OrderService instances sharing one database, so "one order is changed by one
 * request at a time" and "a scheduled job runs once" stay true when the service is scaled out.
 *
 * <ul>
 *   <li>{@link #lock} - waits its turn (reentrant, like the synchronized blocks it replaces). Use in try-with-resources.</li>
 *   <li>{@link #runExclusively} - for scheduled jobs: runs only if no other instance holds the job's lock, else skips.</li>
 * </ul>
 *
 * Each lock is a row with a lease: an instance that dies mid-way frees its lock when the lease runs out. Inside one JVM a
 * striped ReentrantLock goes first so threads of the same instance queue in memory instead of polling the database.
 * {@link #localOnly()} has no database and only does that in-memory part (unit tests, single-process use).
 */
@Component
public class ClusterLocks {
    private static final Logger log = LoggerFactory.getLogger(ClusterLocks.class);
    private static final int STRIPES = 64;
    private static final Timestamp FREE = Timestamp.from(Instant.EPOCH);

    /** What lock() returns; closing it releases the lock. Never throws. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();
    private final ReentrantLock[] local = new ReentrantLock[STRIPES];

    // How long a held lock stays valid without being released (a crashed holder's lock frees itself after this), and how
    // long a caller waits for a lock someone else holds before giving up.
    private final Duration lease = Duration.ofMinutes(2);
    private final Duration maxWait = Duration.ofSeconds(30);
    private static final Duration JOB_COOLDOWN = Duration.ofSeconds(30);

    @Autowired
    public ClusterLocks(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        initLocal();
    }

    private ClusterLocks() {
        this.jdbc = null;
        this.tx = null;
        initLocal();
    }

    public static ClusterLocks localOnly() {
        return new ClusterLocks();
    }

    private void initLocal() {
        for (int i = 0; i < STRIPES; i++) {
            local[i] = new ReentrantLock();
        }
    }

    private ReentrantLock stripe(String name) {
        return local[Math.floorMod(name.hashCode(), STRIPES)];
    }

    public Handle lock(String name) {
        ReentrantLock mem = stripe(name);
        mem.lock();
        boolean first = mem.getHoldCount() == 1;
        if (jdbc != null && first) {
            try {
                acquireFromDatabase(name);
            } catch (RuntimeException e) {
                mem.unlock();
                throw e;
            }
        }
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return; // closing twice must not unlock someone else's hold
            }
            try {
                if (jdbc != null && first) {
                    release(name);
                }
            } finally {
                mem.unlock();
            }
        };
    }

    /** Runs the job unless another instance (or an earlier run still going) holds its lock; returns whether it ran. */
    public boolean runExclusively(String jobName, Runnable job) {
        String name = "job-" + jobName;
        ReentrantLock mem = stripe(name);
        if (!mem.tryLock()) {
            return false;
        }
        try {
            if (jdbc != null && !tryAcquire(name)) {
                log.debug("Job {} is running on another instance - skipped here", jobName);
                return false;
            }
            try {
                job.run();
            } finally {
                if (jdbc != null) {
                    // Stay locked a little after finishing: a quick job must not be run again by another instance whose
                    // timer fired a moment later (cron jobs fire on every instance at the same second).
                    release(name, Timestamp.from(Instant.now().plus(JOB_COOLDOWN)));
                }
            }
            return true;
        } finally {
            mem.unlock();
        }
    }

    private void acquireFromDatabase(String name) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (!tryAcquire(name)) {
            if (System.nanoTime() > deadline) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "This order is being changed by another request - please try again in a moment");
            }
            try {
                Thread.sleep(40 + (long) (Math.random() * 40));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while waiting for a lock");
            }
        }
    }

    boolean tryAcquire(String name) {
        Instant now = Instant.now();
        Timestamp until = Timestamp.from(now.plus(lease));
        Boolean got = tx.execute(status -> {
            int updated = jdbc.update(
                    "update distributed_lock set owner = ?, locked_until = ? where lock_name = ? and (locked_until < ? or owner = ?)",
                    owner, until, name, Timestamp.from(now), owner);
            if (updated > 0) {
                return true;
            }
            try {
                jdbc.update("insert into distributed_lock (lock_name, owner, locked_until) values (?, ?, ?)", name, owner, until);
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        });
        return Boolean.TRUE.equals(got);
    }

    void release(String name) {
        release(name, FREE);
    }

    private void release(String name, Timestamp lockedUntil) {
        try {
            tx.executeWithoutResult(status ->
                    jdbc.update("update distributed_lock set locked_until = ? where lock_name = ? and owner = ?", lockedUntil, name, owner));
        } catch (RuntimeException e) {
            // The lease runs out on its own; never let a release problem fail the work that was already done.
            log.warn("Could not release lock {}: {} (it expires on its own)", name, e.getMessage());
        }
    }
}
