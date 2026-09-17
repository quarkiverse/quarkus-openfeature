package io.quarkiverse.openfeature.runtime;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

/**
 * A pool of instances that are expensive to create and may only be used by one thread at a time,
 * such as WASM evaluation engines.
 * <p>
 * The pool always holds at least {@code minSize} instances, which are created eagerly in
 * the constructor, and grows on demand up to {@value #MAX_FACTOR} * {@code minSize} instances. Only
 * when the pool is full does borrowing wait for an instance to be released, and only for a short
 * while ({@value #MAX_WAIT_MILLIS} ms) before failing.
 * <p>
 * Instances created above the minimum size are destroyed again when they become idle. While
 * the pool is above {@value #PEAK_FACTOR} * {@code minSize} instances, idle instances are
 * destroyed after {@value #FAST_IDLE_TIMEOUT_MINUTES} minute(s). The last {@code minSize}
 * instances above the minimum size are destroyed after {@value #SLOW_IDLE_TIMEOUT_MINUTES}
 * minutes, so that a pool under continuous moderate load doesn't keep creating and destroying
 * instances.
 * <p>
 * Shrinking happens as a side effect of releasing instances, so a pool that grew and then went
 * completely idle keeps the extra instances until it is used again.
 */
public final class Pool<T> {
    private static final Logger log = Logger.getLogger(Pool.class);

    static final int PEAK_FACTOR = 2;
    static final int MAX_FACTOR = 4;

    static final int MAX_WAIT_MILLIS = 10;
    static final int FAST_IDLE_TIMEOUT_MINUTES = 1;
    static final int SLOW_IDLE_TIMEOUT_MINUTES = 10;

    private final String description;
    // instances below minSize are never destroyed
    private final int minSize;
    // instances above minSize and below peakSize are destroyed slowly
    private final int peakSize;
    // instances above peakSize (and below maxSize) are destroyed quickly
    private final int maxSize;
    private final long maxWaitNanos;
    private final long fastIdleTimeoutNanos;
    private final long slowIdleTimeoutNanos;
    private final Supplier<T> creator;
    // wrapped so that it never throws; instances are destroyed as a side effect of releasing
    // and closing, and a failing destroyer must neither break an otherwise successful operation
    // nor stop `close()` from destroying the remaining instances
    private final Consumer<T> destroyer;

    // one permit per instance that may be borrowed, so the pool never exceeds the maximum size
    private final Semaphore permits;
    // LIFO: the most recently released instance is first, so the coldest instance is last
    private final ConcurrentLinkedDeque<Idle<T>> idle = new ConcurrentLinkedDeque<>();
    // number of instances that exist, both idle and currently borrowed
    private final AtomicInteger total = new AtomicInteger();
    private volatile boolean closed;

    /**
     * Creates a pool of {@code minSize} instances that may grow up to {@code 4 * minSize}.
     * The {@code description} is used in log and error messages, it should name a single
     * instance (such as {@code "WASM engine"}).
     */
    public Pool(String description, int minSize, Supplier<T> creator, Consumer<T> destroyer) {
        this(description, minSize, Duration.ofMillis(MAX_WAIT_MILLIS), Duration.ofMinutes(FAST_IDLE_TIMEOUT_MINUTES),
                Duration.ofMinutes(SLOW_IDLE_TIMEOUT_MINUTES), creator, destroyer);
    }

    // visible for testing
    Pool(String description, int minSize, Duration maxWait, Duration fastIdleTimeout, Duration slowIdleTimeout,
            Supplier<T> creator, Consumer<T> destroyer) {
        if (minSize < 1) {
            throw new IllegalArgumentException("Pool size of '" + description + "' must be at least 1");
        }
        this.description = description;
        this.minSize = minSize;
        this.peakSize = PEAK_FACTOR * minSize;
        this.maxSize = MAX_FACTOR * minSize;
        this.maxWaitNanos = maxWait.toNanos();
        this.fastIdleTimeoutNanos = fastIdleTimeout.toNanos();
        this.slowIdleTimeoutNanos = slowIdleTimeout.toNanos();
        this.creator = creator;
        this.destroyer = instance -> {
            try {
                destroyer.accept(instance);
            } catch (Throwable e) {
                log.errorf(e, "Failed to destroy a '%s' instance", description);
            }
        };
        this.permits = new Semaphore(maxSize, true); // intentionally fair

        long now = System.nanoTime();
        try {
            for (int i = 0; i < minSize; i++) {
                idle.addLast(new Idle<>(creator.get(), now));
                total.incrementAndGet();
            }
        } catch (Throwable e) {
            // the pool is never handed to the caller, so the instances created so far
            // would be lost if they were not destroyed here
            destroyIdle();
            throw e;
        }
    }

    /**
     * Borrows an instance from the pool, passes it to {@code action} and returns the instance
     * to the pool afterwards. The instance must not be used after {@code action} returns;
     * ideally, the {@code action} doesn't store the instance anywhere and only uses it locally.
     * <p>
     * When the pool is already at its maximum size but all instances are in use, this method
     * throws an exception.
     */
    public <R> R withInstance(Function<T, R> action) {
        T instance = borrow();
        try {
            return action.apply(instance);
        } finally {
            release(instance);
        }
    }

    /**
     * Destroys all instances in the pool. Instances that are currently borrowed are destroyed
     * when they are released, and an instance that is concurrently being created is destroyed
     * by the thread that created it.
     */
    public void close() {
        closed = true;
        destroyIdle();
    }

    // visible for testing
    T borrow() {
        checkNotClosed();

        // a permit is the right to hold an instance, so there are never more instances
        // than the maximum size; when the pool is full, the waiting thread is parked
        // by the semaphore and woken up by whoever releases an instance, in the order
        // in which the threads started waiting
        try {
            if (!permits.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                log.errorf("Timed out waiting for a '%s' instance, all %d instances are in use",
                        description, maxSize);
                throw new IllegalStateException("Timed out waiting for a '" + description + "' instance");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a '" + description + "' instance", e);
        }

        Idle<T> instance = idle.pollFirst();
        if (instance != null) {
            return instance.instance;
        }

        // shrinking only happens when an instance is released, so creating a new instance
        // is the only thing that can fail here; the permit must be handed back, otherwise
        // the pool would lose capacity with every failure
        try {
            return create();
        } catch (Throwable e) {
            permits.release();
            throw e;
        }
    }

    // visible for testing
    void release(T instance) {
        // the instance must be in the deque before the permit is released,
        // otherwise a woken up thread could find no instance and create one
        returnToIdle(instance, System.nanoTime());
        permits.release();
        if (closed) {
            return;
        }
        evictIdle();
    }

    // puts an instance back among the idle instances as the hottest one. The pool may have been
    // closed in the meantime, in which case the instance must be destroyed instead of being
    // retained in a pool that nobody will ever drain again.
    private void returnToIdle(T instance, long idleSince) {
        idle.addFirst(new Idle<>(instance, idleSince));
        if (closed) {
            destroyIdle();
        }
    }

    // visible for testing
    int size() {
        return total.get();
    }

    private T create() {
        // the pool may have been closed after this thread passed the check in `borrow()`,
        // and creating an instance just to destroy it again is a waste
        checkNotClosed();
        log.debugf("Growing the pool of '%s' instances", description);
        T instance = creator.get();
        total.incrementAndGet();
        // the pool may also have been closed while the instance was being created; `close()`
        // drains the idle instances and this one was not among them, so nobody else is ever
        // going to destroy it
        if (closed) {
            total.decrementAndGet();
            destroyer.accept(instance);
            checkNotClosed();
        }
        return instance;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Pool of '" + description + "' instances is closed");
        }
    }

    private void evictIdle() {
        while (true) {
            int total = this.total.get();
            if (total <= minSize) {
                return;
            }
            long idleTimeoutNanos = total > peakSize ? fastIdleTimeoutNanos : slowIdleTimeoutNanos;
            Idle<T> coldest = idle.peekLast();
            if (coldest == null || System.nanoTime() - coldest.idleSince < idleTimeoutNanos) {
                return;
            }
            // reserve the removal first, so that the pool can never shrink below the minimum size
            if (!this.total.compareAndSet(total, total - 1)) {
                continue;
            }
            // Remove exactly the instance that was found to be cold enough, never just whatever
            // happens to be last by now: an instance that is still hot must not be taken out of
            // the deque even temporarily, because a concurrent `borrow()` would see an empty
            // pool and create a superfluous instance. The permits still keep the pool within
            // the maximum size, the cost is pointless creation and destruction.
            if (!idle.removeLastOccurrence(coldest)) {
                // someone else was faster and borrowed or evicted that instance
                this.total.incrementAndGet();
                return;
            }
            log.debugf("Shrinking the pool of '%s' instances", description);
            destroyer.accept(coldest.instance);
        }
    }

    private void destroyIdle() {
        Idle<T> instance;
        while ((instance = idle.pollFirst()) != null) {
            total.decrementAndGet();
            destroyer.accept(instance.instance);
        }
    }

    // Intentionally not a record. Eviction identifies an idle instance by removing exactly
    // the object it previously found to be the coldest one, so equality must be identity.
    private static final class Idle<T> {
        final T instance;
        final long idleSince;

        Idle(T instance, long idleSince) {
            this.instance = instance;
            this.idleSince = idleSince;
        }
    }
}
