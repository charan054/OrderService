package com.example.orderservice;

import com.example.orderservice.service.ClusterLocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs the real lock table on H2. Two ClusterLocks objects stand in for two OrderService instances (each has its own owner id).
@SpringBootTest
@ActiveProfiles("test")
class ClusterLocksTest {

    @Autowired
    private ClusterLocks instanceA;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    private ClusterLocks otherInstance() {
        return new ClusterLocks(jdbc, transactions);
    }

    @Test
    void aLockIsReentrantWithinOneThread() {
        try (var outer = instanceA.lock("order-reentrant")) {
            try (var inner = instanceA.lock("order-reentrant")) {
                assertEquals(1, jdbc.queryForObject(
                        "select count(*) from distributed_lock where lock_name = 'order-reentrant'", Integer.class));
            }
            // still held after the inner close (only the outermost close releases it)
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from distributed_lock where lock_name = 'order-reentrant' and locked_until > ?",
                    Integer.class, Timestamp.from(Instant.now())));
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from distributed_lock where lock_name = 'order-reentrant' and locked_until < ?",
                Integer.class, Timestamp.from(Instant.now())));
    }

    @Test
    void anotherInstanceWaitsUntilTheLockIsReleased() throws Exception {
        ClusterLocks b = otherInstance();
        CountDownLatch bTriedToLock = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();
        AtomicInteger bGotItAt = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (var held = instanceA.lock("order-contended")) {
                var waiting = pool.submit(() -> {
                    bTriedToLock.countDown();
                    try (var got = b.lock("order-contended")) {
                        bGotItAt.set(order.incrementAndGet());
                    }
                });
                assertTrue(bTriedToLock.await(5, TimeUnit.SECONDS));
                Thread.sleep(300);
                assertEquals(0, bGotItAt.get(), "B must still be waiting while A holds the lock");
                order.incrementAndGet(); // A's turn finishes first
                held.close();
                waiting.get(10, TimeUnit.SECONDS);
            }
            assertEquals(2, bGotItAt.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLockWhoseLeaseHasExpiredCanBeTakenOver() {
        jdbc.update("insert into distributed_lock (lock_name, owner, locked_until) values (?, ?, ?)",
                "order-crashed", "dead-instance", Timestamp.from(Instant.now().minusSeconds(60)));

        try (var got = instanceA.lock("order-crashed")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from distributed_lock where lock_name = 'order-crashed' and owner <> 'dead-instance'", Integer.class));
        }
    }

    @Test
    void aJobRunsOnOneInstanceAndIsSkippedOnTheOtherEvenRightAfterItFinished() {
        ClusterLocks b = otherInstance();
        AtomicInteger runs = new AtomicInteger();

        assertTrue(instanceA.runExclusively("digest-test", runs::incrementAndGet));
        assertFalse(b.runExclusively("digest-test", runs::incrementAndGet), "B's timer fired just after A finished");
        assertEquals(1, runs.get());
    }

    @Test
    void aJobIsSkippedWhileAnotherInstanceIsStillRunningIt() {
        ClusterLocks b = otherInstance();
        AtomicInteger bRuns = new AtomicInteger();

        instanceA.runExclusively("long-job", () ->
                assertFalse(b.runExclusively("long-job", bRuns::incrementAndGet)));

        assertEquals(0, bRuns.get());
    }

    @Test
    void theLocalOnlyVersionStillMakesThreadsTakeTurns() throws Exception {
        ClusterLocks local = ClusterLocks.localOnly();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(pool.submit(() -> {
                    for (int n = 0; n < 20; n++) {
                        try (var h = local.lock("order-1")) {
                            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Thread.yield();
                            inside.decrementAndGet();
                        }
                    }
                }));
            }
            for (var t : tasks) {
                t.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, maxInside.get());
    }
}
