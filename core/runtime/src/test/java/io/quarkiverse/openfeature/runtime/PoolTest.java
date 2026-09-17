package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

// depends on per-method test lifecycle!
public class PoolTest {
    private static final Duration MAX_WAIT = Duration.ofMillis(10);
    // long enough that no instance can be evicted during a test
    private static final Duration NEVER_IDLE_TIMEOUT = Duration.ofHours(1);
    // short enough that every idle instance is immediately eligible for eviction
    private static final Duration IMMEDIATE_IDLE_TIMEOUT = Duration.ZERO;
    // short enough that a test can wait for it, long enough that nothing expires by accident
    private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofMillis(200);

    private final Instances instances = new Instances();

    static class Instance {
        final int id;
        volatile boolean destroyed;

        Instance(int id) {
            this.id = id;
        }
    }

    // thrown by a destroyer that fails with an `Error` instead of a `RuntimeException`
    static final class DestructionError extends Error {
        DestructionError(String message) {
            super(message);
        }
    }

    static class Instances implements Supplier<Instance>, Consumer<Instance> {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger destructionAttempts = new AtomicInteger();
        final List<Instance> destroyed = Collections.synchronizedList(new ArrayList<>());
        volatile boolean destructionFails;
        volatile boolean destructionFailsWithError;
        // runs during the first destruction, so that a test can interfere with the pool
        // at the exact moment when an instance is being destroyed
        volatile Runnable onFirstDestruction;

        @Override
        public Instance get() {
            return new Instance(created.incrementAndGet());
        }

        @Override
        public void accept(Instance instance) {
            destructionAttempts.incrementAndGet();
            Runnable hook = onFirstDestruction;
            if (hook != null) {
                onFirstDestruction = null;
                hook.run();
            }
            if (destructionFailsWithError) {
                throw new DestructionError("cannot destroy");
            }
            if (destructionFails) {
                throw new IllegalStateException("cannot destroy");
            }
            instance.destroyed = true;
            destroyed.add(instance);
        }
    }

    private Pool<Instance> pool(int minSize, Duration idleTimeout) {
        return pool(minSize, idleTimeout, idleTimeout);
    }

    private Pool<Instance> pool(int minSize, Duration fastIdleTimeout, Duration slowIdleTimeout) {
        return pool(instances, minSize, fastIdleTimeout, slowIdleTimeout);
    }

    private Pool<Instance> pool(Supplier<Instance> factory, int minSize, Duration fastIdleTimeout,
            Duration slowIdleTimeout) {
        return new Pool<>("test instance", minSize, MAX_WAIT, fastIdleTimeout, slowIdleTimeout,
                factory, instances);
    }

    private static List<Instance> borrowAll(Pool<Instance> pool, int count) {
        List<Instance> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(pool.borrow());
        }
        return result;
    }

    private static void releaseAll(Pool<Instance> pool, List<Instance> instances) {
        for (Instance instance : instances) {
            pool.release(instance);
        }
    }

    @Test
    public void minimumSizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> pool(0, NEVER_IDLE_TIMEOUT));
        assertEquals(0, instances.created.get());
    }

    @Test
    public void createsMinimumNumberOfInstancesEagerly() {
        Pool<Instance> pool = pool(3, NEVER_IDLE_TIMEOUT);

        assertEquals(3, instances.created.get());
        assertEquals(3, pool.size());
    }

    @Test
    public void reusesReleasedInstance() {
        Pool<Instance> pool = pool(2, NEVER_IDLE_TIMEOUT);

        Instance instance = pool.borrow();
        pool.release(instance);

        assertSame(instance, pool.borrow());
        assertEquals(2, instances.created.get());
    }

    @Test
    public void withInstanceReleasesOnSuccessAndOnFailure() {
        Pool<Instance> pool = pool(1, NEVER_IDLE_TIMEOUT);

        int id = pool.withInstance(instance -> instance.id);
        assertEquals(1, id);
        assertThrows(IllegalStateException.class, () -> pool.withInstance(instance -> {
            throw new IllegalStateException("boom");
        }));

        // the instance was released both times, so it can be borrowed again without growing
        id = pool.withInstance(instance -> instance.id);
        assertEquals(1, id);
        assertEquals(1, instances.created.get());
    }

    @Test
    public void growsUpToFourTimesTheMinimumSize() {
        Pool<Instance> pool = pool(2, NEVER_IDLE_TIMEOUT);

        List<Instance> borrowed = borrowAll(pool, 8);

        assertEquals(8, instances.created.get());
        assertEquals(8, pool.size());
        assertEquals(8, borrowed.stream().distinct().count());
    }

    @Test
    public void timesOutWhenSaturated() {
        Pool<Instance> pool = pool(1, NEVER_IDLE_TIMEOUT);
        borrowAll(pool, 4);

        long start = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class, pool::borrow);
        long elapsed = System.nanoTime() - start;

        assertTrue(e.getMessage().contains("Timed out"), e.getMessage());
        assertTrue(elapsed >= MAX_WAIT.toNanos(), "should wait at least " + MAX_WAIT);
        // generous upper bound, only to catch waiting for far too long
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "should not wait much longer than " + MAX_WAIT);
        assertEquals(4, pool.size());
    }

    @Test
    public void waitsForInstanceReleasedByAnotherThread() throws Exception {
        Pool<Instance> pool = pool(1, NEVER_IDLE_TIMEOUT);
        List<Instance> borrowed = borrowAll(pool, 4);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch borrowing = new CountDownLatch(1);
            Future<Instance> borrow = executor.submit(() -> {
                borrowing.countDown();
                return pool.borrow();
            });
            assertTrue(borrowing.await(5, TimeUnit.SECONDS));
            pool.release(borrowed.get(0));

            assertSame(borrowed.get(0), borrow.get(5, TimeUnit.SECONDS));
            assertEquals(4, instances.created.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void interruptedWhileWaiting() throws Exception {
        Pool<Instance> pool = pool(1, NEVER_IDLE_TIMEOUT);
        borrowAll(pool, 4);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                pool.borrow();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        thread.start();
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("Interrupted"), failure.get().getMessage());
        assertInstanceOf(InterruptedException.class, failure.get().getCause());
    }

    @Test
    public void shrinksBackToMinimumSizeWhenInstancesAreIdle() {
        Pool<Instance> pool = pool(2, IMMEDIATE_IDLE_TIMEOUT);

        List<Instance> borrowed = borrowAll(pool, 8);
        assertEquals(8, pool.size());
        releaseAll(pool, borrowed);

        assertEquals(2, pool.size());
        assertEquals(6, instances.destroyed.size());
        // the instances that are kept are the most recently released ones
        List<Instance> kept = new ArrayList<>(borrowed);
        kept.removeAll(instances.destroyed);
        assertEquals(List.of(borrowed.get(6), borrowed.get(7)), kept);
    }

    @Test
    public void shrinksPeakCapacityQuicklyAndTheRestSlowly() {
        Pool<Instance> pool = pool(2, IMMEDIATE_IDLE_TIMEOUT, NEVER_IDLE_TIMEOUT);

        releaseAll(pool, borrowAll(pool, 8));

        // the peak capacity above 2 * minimum size is gone, the rest is kept for much longer
        assertEquals(4, pool.size());
        assertEquals(4, instances.destroyed.size());
    }

    @Test
    public void doesNotShrinkBeforeTheIdleTimeout() {
        Pool<Instance> pool = pool(1, NEVER_IDLE_TIMEOUT);

        releaseAll(pool, borrowAll(pool, 4));

        assertEquals(4, pool.size());
        assertEquals(0, instances.destroyed.size());
    }

    @Test
    public void slotIsReleasedWhenInstanceCreationFails() {
        Supplier<Instance> failingFactory = () -> {
            Instance instance = instances.get();
            if (instance.id == 2) {
                throw new IllegalStateException("cannot create");
            }
            return instance;
        };
        Pool<Instance> pool = pool(failingFactory, 1, NEVER_IDLE_TIMEOUT, NEVER_IDLE_TIMEOUT);

        pool.borrow();
        assertThrows(IllegalStateException.class, pool::borrow);
        assertEquals(1, pool.size());

        // the failed attempt did not consume a slot, the pool can still grow
        assertNotNull(pool.borrow());
        assertEquals(2, pool.size());
    }

    @Test
    public void instancesCreatedBeforeAFailureInTheConstructorAreDestroyed() {
        Supplier<Instance> failingFactory = () -> {
            Instance instance = instances.get();
            if (instance.id == 3) {
                throw new IllegalStateException("cannot create");
            }
            return instance;
        };

        assertThrows(IllegalStateException.class,
                () -> pool(failingFactory, 3, NEVER_IDLE_TIMEOUT, NEVER_IDLE_TIMEOUT));

        // the pool is never handed to the caller, so it cannot destroy the instances later
        assertEquals(3, instances.created.get());
        assertEquals(2, instances.destroyed.size());
    }

    @Test
    public void releaseSucceedsWhenDestructionFailsDuringEviction() throws InterruptedException {
        Pool<Instance> pool = pool(2, SHORT_IDLE_TIMEOUT);

        List<Instance> borrowed = borrowAll(pool, 8);
        releaseAll(pool, borrowed.subList(0, 7));
        // all instances were just released, so none of them is eligible for eviction yet
        assertEquals(8, pool.size());
        assertEquals(0, instances.destroyed.size());

        // now they all are, so the last release shrinks the pool back to the minimum size
        Thread.sleep(2 * SHORT_IDLE_TIMEOUT.toMillis());

        instances.destructionFails = true;
        pool.release(borrowed.get(7));
        instances.destructionFails = false;

        // a failed destruction still counts as an eviction, so the pool shrinks all the way
        // back to the minimum size and releasing succeeds
        assertEquals(8, instances.created.get());
        assertEquals(2, pool.size());

        // the pool still has both of its instances, so it doesn't have to create new ones
        assertEquals(2, borrowAll(pool, 2).stream().distinct().count());
        assertEquals(8, instances.created.get());
    }

    @Test
    public void borrowSucceedsWhileAnotherThreadIsStuckEvicting() throws Exception {
        Pool<Instance> pool = pool(1, SHORT_IDLE_TIMEOUT);

        List<Instance> borrowed = borrowAll(pool, 4);
        releaseAll(pool, borrowed.subList(0, 3));
        // now every idle instance is eligible for eviction, so the last release evicts
        Thread.sleep(2 * SHORT_IDLE_TIMEOUT.toMillis());

        CountDownLatch evicting = new CountDownLatch(1);
        CountDownLatch finishEviction = new CountDownLatch(1);
        instances.onFirstDestruction = () -> {
            evicting.countDown();
            try {
                finishEviction.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> stuck = executor.submit(() -> pool.release(borrowed.get(3)));
            assertTrue(evicting.await(5, TimeUnit.SECONDS));

            // eviction runs before the release returns, so a slow destruction delays it
            assertFalse(stuck.isDone());

            // the release hands its permit back before it evicts, so a borrow is not
            // blocked by however long the destruction takes
            long start = System.nanoTime();
            Instance justBorrowed = pool.borrow();
            long elapsed = System.nanoTime() - start;

            assertNotNull(justBorrowed);
            assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1), "borrow took " + elapsed + " ns");

            finishEviction.countDown();
            stuck.get(5, TimeUnit.SECONDS);
        } finally {
            finishEviction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void actionSucceedsWhenDestructionFailsDuringRelease() {
        Pool<Instance> pool = pool(1, IMMEDIATE_IDLE_TIMEOUT);

        // grows the pool above the minimum size, so that releasing evicts an instance
        Instance borrowed = pool.borrow();

        instances.destructionFails = true;
        assertEquals("result", pool.withInstance(instance -> "result"));
        instances.destructionFails = false;

        assertEquals(1, pool.size());
        pool.release(borrowed);
        assertSame(borrowed, pool.borrow());
    }

    @Test
    public void closeDestroysIdleInstances() {
        Pool<Instance> pool = pool(3, NEVER_IDLE_TIMEOUT);

        pool.close();

        assertEquals(3, instances.destroyed.size());
        assertEquals(0, pool.size());
        assertThrows(IllegalStateException.class, pool::borrow);
    }

    @Test
    public void closeDestroysBorrowedInstancesWhenTheyAreReleased() {
        Pool<Instance> pool = pool(2, NEVER_IDLE_TIMEOUT);

        Instance borrowed = pool.borrow();
        pool.close();

        assertEquals(1, instances.destroyed.size());
        assertFalse(borrowed.destroyed);

        pool.release(borrowed);

        assertEquals(2, instances.destroyed.size());
        assertTrue(borrowed.destroyed);
        assertEquals(0, pool.size());
    }

    @Test
    public void actionSucceedsWhenDestructionThrowsAnErrorDuringRelease() {
        Pool<Instance> pool = pool(1, IMMEDIATE_IDLE_TIMEOUT);

        // grows the pool above the minimum size, so that releasing evicts an instance
        Instance borrowed = pool.borrow();

        instances.destructionFailsWithError = true;
        assertEquals("result", pool.withInstance(instance -> "result"));
        instances.destructionFailsWithError = false;

        assertEquals(1, instances.destructionAttempts.get());
        assertEquals(1, pool.size());
        pool.release(borrowed);
        assertSame(borrowed, pool.borrow());
    }

    @Test
    public void closeDestroysEveryInstanceWhenDestructionThrowsAnError() {
        Pool<Instance> pool = pool(3, NEVER_IDLE_TIMEOUT);

        instances.destructionFailsWithError = true;
        pool.close();
        instances.destructionFailsWithError = false;

        // a destroyer that fails on the first instance must not stop the pool from
        // attempting to destroy the remaining ones
        assertEquals(3, instances.destructionAttempts.get());
        assertEquals(0, instances.destroyed.size());
        assertEquals(0, pool.size());
    }

    @Test
    public void instanceCreatedWhileThePoolIsClosingIsDestroyed() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finishCreation = new CountDownLatch(1);
        Supplier<Instance> slowFactory = () -> {
            Instance instance = instances.get();
            if (instance.id == 2) {
                creating.countDown();
                try {
                    finishCreation.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return instance;
        };
        Pool<Instance> pool = pool(slowFactory, 1, NEVER_IDLE_TIMEOUT, NEVER_IDLE_TIMEOUT);

        // the only instance is taken, so the next borrow has to create one
        Instance borrowed = pool.borrow();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Instance> borrow = executor.submit(() -> {
                return pool.borrow();
            });
            assertTrue(creating.await(5, TimeUnit.SECONDS));

            // the pool is closed while the instance is still being created, so `close()`
            // cannot see it; handing it out would leak it
            pool.close();
            finishCreation.countDown();

            ExecutionException e = assertThrows(ExecutionException.class, () -> borrow.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("is closed"), e.getCause().getMessage());
        } finally {
            finishCreation.countDown();
            executor.shutdownNow();
        }

        assertEquals(2, instances.created.get());
        assertEquals(1, instances.destroyed.size());
        assertEquals(2, instances.destroyed.get(0).id);

        pool.release(borrowed);
        assertEquals(2, instances.destroyed.size());
        assertEquals(0, pool.size());
    }

    @Test
    public void concurrentCloseDestroysEveryInstance() throws Exception {
        // every idle instance is immediately eligible, so eviction runs on every operation
        Pool<Instance> pool = pool(4, IMMEDIATE_IDLE_TIMEOUT);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < 2000; j++) {
                        try {
                            pool.withInstance(borrowed -> null);
                        } catch (IllegalStateException e) {
                            // the pool was closed by the main thread, which is expected
                            return null;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            pool.close();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // no instance is borrowed anymore, so nothing may be left behind
        assertEquals(instances.created.get(), instances.destroyed.size());
        assertEquals(0, pool.size());
    }

    @Test
    public void concurrentBorrowAndReleaseNeverExceedsTheMaximumSize() throws Exception {
        int minSize = 4;
        int maxSize = Pool.MAX_FACTOR * minSize;
        Pool<Instance> pool = pool(minSize, NEVER_IDLE_TIMEOUT);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < 500; j++) {
                        Instance instance = pool.withInstance(borrowed -> borrowed);
                        assertFalse(instance.destroyed);
                        assertTrue(pool.size() <= maxSize);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(instances.created.get() <= maxSize, "created " + instances.created.get() + " instances");
        assertTrue(pool.size() <= maxSize);
        assertTrue(pool.size() >= minSize);
    }

    @Test
    public void concurrentEvictionNeverHandsOutADestroyedInstance() throws Exception {
        int minSize = 4;
        int maxSize = Pool.MAX_FACTOR * minSize;
        // every idle instance is immediately eligible, so eviction runs on every operation
        Pool<Instance> pool = pool(minSize, IMMEDIATE_IDLE_TIMEOUT);

        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < 2000; j++) {
                        pool.withInstance(borrowed -> {
                            assertFalse(borrowed.destroyed, "borrowed an already destroyed instance");
                            return null;
                        });
                        int size = pool.size();
                        assertTrue(size >= minSize && size <= maxSize, "pool size is " + size);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(minSize, pool.size());
        assertEquals(instances.created.get() - minSize, instances.destroyed.size());
    }
}
