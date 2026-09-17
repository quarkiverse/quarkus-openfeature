package io.quarkiverse.openfeature.gofeatureflag.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Measures the cost of a GO Feature Flag WASM engine instance, which is what the size of the
 * engine pool and its timeouts are based on. This is not a test: it asserts nothing and its
 * results depend on the machine.
 */
public class GoFeatureFlagWasmEngineMeasurement {
    private static final String BOOL_INPUT = """
            {
              "flagKey": "bool-flag",
              "flag": {
                "variations": {"enabled": true, "disabled": false},
                "defaultRule": {"variation": "enabled"}
              },
              "evalContext": {"targetingKey": "user-1"},
              "flagContext": {"defaultSdkValue": false}
            }
            """;

    private static final String STRING_INPUT = """
            {
              "flagKey": "string-flag",
              "flag": {
                "variations": {"greeting": "hello", "parting": "goodbye"},
                "defaultRule": {"variation": "greeting"}
              },
              "evalContext": {"targetingKey": "user-1"},
              "flagContext": {"defaultSdkValue": "none"}
            }
            """;

    private static final int ENGINES = 8;
    private static final int WARMUP = 20_000;
    private static final int EVALUATIONS = 200_000;
    private static final int LOAD_SECONDS = 5;

    @Test
    public void measureSingleEngine() {
        long heapBefore = heapUsed();

        List<GoFeatureFlagWasmEngine> engines = new ArrayList<>();
        long[] creationNanos = new long[ENGINES];
        for (int i = 0; i < ENGINES; i++) {
            long start = System.nanoTime();
            GoFeatureFlagWasmEngine engine = new GoFeatureFlagWasmEngine();
            creationNanos[i] = System.nanoTime() - start;
            engines.add(engine);
        }

        GoFeatureFlagWasmEngine engine = engines.get(0);
        System.out.println("=== GO Feature Flag WASM engine ===");
        System.out.printf("creation: first %.1f ms, rest median %.1f ms, rest max %.1f ms%n",
                millis(creationNanos[0]),
                millis(median(Arrays.copyOfRange(creationNanos, 1, ENGINES))),
                millis(max(Arrays.copyOfRange(creationNanos, 1, ENGINES))));

        for (int i = 0; i < WARMUP; i++) {
            engine.evaluate(BOOL_INPUT);
            engine.evaluate(STRING_INPUT);
        }

        measureEvaluation("boolean evaluation", () -> engine.evaluate(BOOL_INPUT));
        measureEvaluation("string evaluation", () -> engine.evaluate(STRING_INPUT));

        long heapAfter = heapUsed();

        System.out.println("WASM memory per instance: " + pagesMB(engine.memoryPages()));
        System.out.printf("JVM heap per instance: %.1f MB (%d instances, %.1f MB total)%n",
                mb(heapAfter - heapBefore) / ENGINES, ENGINES, mb(heapAfter - heapBefore));
    }

    /**
     * Drives the whole engine pool from many threads at once, to see how much of the load it
     * sheds through the borrow timeout. 200 threads is the default size of the Quarkus worker
     * pool, so that is roughly the worst case concurrency of a blocking application.
     */
    @Test
    public void measurePoolUnderLoad() throws Exception {
        measurePool(8, 16);
        measurePool(16, 16);
        measurePool(32, 16);
        measurePool(64, 16);
        measurePool(128, 16);
        measurePool(256, 16);

        measurePool(256, 64);
    }

    private static void measurePool(int threads, int minSize) throws Exception {
        GoFeatureFlagWasmEnginePool pool = new GoFeatureFlagWasmEnginePool(minSize);
        AtomicInteger timeouts = new AtomicInteger();
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOAD_SECONDS);

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
                        pool.evaluate(BOOL_INPUT);
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

        System.out.printf("=== GO Feature Flag pool, %d threads, minimum %d, maximum %d ===%n",
                threads, minSize, 4 * minSize);
        long[] all = latencies.stream().flatMapToLong(Arrays::stream).toArray();
        System.out.printf("%d evaluations in %d s (%.0f/s), %d timeouts%n",
                all.length, LOAD_SECONDS, (double) all.length / LOAD_SECONDS, timeouts.get());
        printStatistics("borrow + evaluate", all);
    }

    private static void measureEvaluation(String what, Runnable evaluation) {
        long[] nanos = new long[EVALUATIONS];
        for (int i = 0; i < EVALUATIONS; i++) {
            long start = System.nanoTime();
            evaluation.run();
            nanos[i] = System.nanoTime() - start;
        }
        printStatistics(what, nanos);
    }

    private static void printStatistics(String what, long[] nanos) {
        Arrays.sort(nanos);
        long sum = 0;
        for (long each : nanos) {
            sum += each;
        }
        System.out.printf("%s: avg %.1f us, p50 %.1f us, p80 %.1f us, p90 %.1f us, p95 %.1f us,"
                + " p99 %.1f us, p99.9 %.1f us, max %.1f us%n",
                what, micros(sum / nanos.length), micros(percentile(nanos, 0.5)),
                micros(percentile(nanos, 0.8)), micros(percentile(nanos, 0.9)),
                micros(percentile(nanos, 0.95)), micros(percentile(nanos, 0.99)),
                micros(percentile(nanos, 0.999)), micros(nanos[nanos.length - 1]));
    }

    // the array must be sorted
    private static long percentile(long[] nanos, double percentile) {
        return nanos[(int) (nanos.length * percentile)];
    }

    private static double micros(long nanos) {
        return nanos / 1000.0;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long max(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length - 1];
    }

    private static String pagesMB(int pages) {
        return String.format("%.1f MB (%d pages)", pages * 64 / 1024.0, pages);
    }

    private static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static long heapUsed() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
