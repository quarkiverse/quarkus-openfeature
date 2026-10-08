package io.quarkiverse.openfeature.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Compares a fair and an unfair {@link Pool} semaphore under load. Fairness is known to cost
 * throughput in general, and the question is whether the pool pays that cost: an instance is
 * held for microseconds, so the handoff may well dominate. The other half of the question is
 * what fairness buys, which is the tail: an unfair semaphore can let a thread move ahead of
 * threads that are already waiting, and a thread that loses repeatedly runs into the borrow
 * timeout and sheds its request.
 * <p>
 * The pooled instance is synthetic, a busy loop of roughly the cost of a WASM flag evaluation,
 * so that the measurement is about the semaphore and not about an engine. How long an instance
 * is held is the one thing that matters, and the two engines differ by a factor of 35, so the
 * duration is settable with {@code -Dpool.work.micros}: the Flipt engine evaluates in about
 * 6 us and the GO Feature Flag engine in about 200 us, as their own {@code *Measurement}
 * harnesses report. This is not a test: it asserts nothing and its results depend on
 * the machine.
 */
public class PoolFairnessMeasurement {
    // how long an instance is held, the default being roughly the Flipt engine
    private static final int TARGET_WORK_MICROS = Integer.getInteger("pool.work.micros", 5);
    private static final Duration DEFAULT_MAX_WAIT = Duration.ofMillis(Pool.MAX_WAIT_MILLIS);
    private static final Duration[] MAX_WAITS = {
            Duration.ofMillis(10), Duration.ofMillis(15), Duration.ofMillis(20),
            Duration.ofMillis(25), Duration.ofMillis(30), Duration.ofMillis(35),
            Duration.ofMillis(40), Duration.ofMillis(45), Duration.ofMillis(50),
    };
    private static final int LOAD_SECONDS = 5;
    private static final int ROUNDS = 3;
    private static final int WARMUP_SECONDS = 1;

    private static int workIterations;

    private static long sink; // prevents dead code elimination, otherwise unused

    @Test
    public void measureFairness() throws Exception {
        workIterations = calibrate();
        System.out.printf("=== Pool fairness, work of %d us (%d iterations) ===%n",
                TARGET_WORK_MICROS, workIterations);

        // one round that nobody looks at, so that the busy loop and the pool are compiled
        measure(16, 8, true, DEFAULT_MAX_WAIT, WARMUP_SECONDS);
        measure(16, 8, false, DEFAULT_MAX_WAIT, WARMUP_SECONDS);

        for (int threads : new int[] { 8, 16, 32, 64, 128, 256 }) {
            measure(threads, 8, true, DEFAULT_MAX_WAIT, LOAD_SECONDS);
            measure(threads, 8, false, DEFAULT_MAX_WAIT, LOAD_SECONDS);
        }

        // the realistic worst case of a blocking application: the default Quarkus worker
        // pool against the default engine pool size, and against one large enough to hold
        // every worker thread at once
        measure(200, 16, true, DEFAULT_MAX_WAIT, LOAD_SECONDS);
        measure(200, 16, false, DEFAULT_MAX_WAIT, LOAD_SECONDS);

        measure(256, 64, true, DEFAULT_MAX_WAIT, LOAD_SECONDS);
        measure(256, 64, false, DEFAULT_MAX_WAIT, LOAD_SECONDS);
    }

    @Test
    public void measureBorrowTimeoutUnfair() throws Exception {
        workIterations = calibrate();
        System.out.printf("=== Borrow timeout, unfair, work of %d us (%d iterations) ===%n",
                TARGET_WORK_MICROS, workIterations);

        measure(16, 8, false, DEFAULT_MAX_WAIT, WARMUP_SECONDS);

        // the rounds are interleaved rather than repeated per timeout, so that whatever the
        // machine does over the few minutes this takes affects every timeout equally
        for (int round = 1; round <= ROUNDS; round++) {
            System.out.printf("--- round %d of %d ---%n", round, ROUNDS);
            for (Duration maxWait : MAX_WAITS) {
                measure(200, 16, false, maxWait, LOAD_SECONDS);
            }
            for (Duration maxWait : MAX_WAITS) {
                measure(256, 8, false, maxWait, LOAD_SECONDS);
            }
        }
    }

    private static void measure(int threads, int minSize, boolean fair, Duration maxWait, int seconds) throws Exception {
        Pool<Object> pool = new Pool<>("instance", minSize, maxWait,
                Duration.ofMinutes(Pool.FAST_IDLE_TIMEOUT_MINUTES),
                Duration.ofMinutes(Pool.SLOW_IDLE_TIMEOUT_MINUTES),
                Object::new, instance -> {
                }, fair);

        AtomicInteger timeouts = new AtomicInteger();
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                List<Long> nanos = new ArrayList<>();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                while (System.nanoTime() < end) {
                    long began = System.nanoTime();
                    try {
                        pool.withInstance(instance -> work());
                        nanos.add(System.nanoTime() - began);
                    } catch (IllegalStateException e) {
                        timeouts.incrementAndGet();
                    }
                }
                latencies.add(nanos.stream().mapToLong(Long::longValue).toArray());
            });
            worker.start();
            workers.add(worker);
        }

        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        pool.close();

        if (seconds != LOAD_SECONDS) { // the warmup round
            return;
        }

        long[] all = latencies.stream().flatMapToLong(Arrays::stream).toArray();
        System.out.printf("--- %d threads, minimum %d, maximum %d, %s, wait %d ms ---%n",
                threads, minSize, Pool.MAX_FACTOR * minSize, fair ? "fair" : "unfair", maxWait.toMillis());
        System.out.printf("%d evaluations in %d s (%.0f/s), %d timeouts (%.2f %%)%n",
                all.length, seconds, (double) all.length / seconds, timeouts.get(),
                100.0 * timeouts.get() / (all.length + timeouts.get()));
        printStatistics("borrow + work", all);
    }

    // Simulated work: a busy loop, so that it occupies the instance without parking
    // the thread, which is what a WASM evaluation does too. The result has to be consumed
    // in a way the JIT cannot see through, otherwise the whole loop is dead code and is
    // compiled away once the method gets hot. The comparison is never true in practice,
    // but the compiler does not know that and so has to keep the loop.
    private static long work() {
        long result = 0;
        for (int i = 0; i < workIterations; i++) {
            result = result * 31 + i;
        }
        if (result == 42) {
            sink = result;
        }
        return result;
    }

    // Finds the number of busy loop iterations that takes about TARGET_WORK_MICROS. Each round
    // measures for a fixed time rather than a fixed number of calls, so that calibrating for
    // a long work duration doesn't take proportionally longer.
    private static int calibrate() {
        long target = TimeUnit.MICROSECONDS.toNanos(TARGET_WORK_MICROS);
        workIterations = 1000;
        for (int round = 0; round < 10; round++) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
            int calls = 0;
            long start = System.nanoTime();
            do {
                work();
                calls++;
            } while (System.nanoTime() < deadline);
            long perCall = (System.nanoTime() - start) / calls;
            if (perCall > 0) {
                workIterations = (int) Math.max(1, workIterations * target / perCall);
            }
        }

        // the calibration is only as good as the busy loop surviving compilation, and a loop
        // that was optimized away calibrates to an absurd iteration count and silently turns
        // the whole measurement into noise, so the achieved duration is checked and reported
        long start = System.nanoTime();
        int calls = 0;
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(200);
        do {
            work();
            calls++;
        } while (System.nanoTime() < deadline);
        double achievedMicros = (System.nanoTime() - start) / 1000.0 / calls;
        if (achievedMicros < TARGET_WORK_MICROS * 0.8 || achievedMicros > TARGET_WORK_MICROS * 1.25) {
            throw new AssertionError(String.format(
                    "Calibration failed: asked for %d us, got %.2f us with %d iterations",
                    TARGET_WORK_MICROS, achievedMicros, workIterations));
        }
        System.out.printf("calibrated to %.2f us per call%n", achievedMicros);
        return workIterations;
    }

    private static void printStatistics(String what, long[] nanos) {
        Arrays.sort(nanos);
        long sum = 0;
        for (long each : nanos) {
            sum += each;
        }
        System.out.printf("%s: avg %.1f us, p50 %.1f us, p90 %.1f us, p99 %.1f us,"
                + " p99.9 %.1f us, max %.1f us%n",
                what, micros(sum / nanos.length), micros(percentile(nanos, 0.5)),
                micros(percentile(nanos, 0.9)), micros(percentile(nanos, 0.99)),
                micros(percentile(nanos, 0.999)), micros(nanos[nanos.length - 1]));
    }

    // the array must be sorted
    private static long percentile(long[] nanos, double percentile) {
        return nanos[(int) (nanos.length * percentile)];
    }

    private static double micros(long nanos) {
        return nanos / 1000.0;
    }
}
