package io.quarkiverse.openfeature.flipt.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Measures the cost of a Flipt WASM engine instance, which is what the size of the engine pool
 * and its timeouts are based on. This is not a test: it asserts nothing and its results depend
 * on the machine.
 */
public class FliptWasmEngineMeasurement {
    private static final String SNAPSHOT = """
            {
              "namespace": {"key": "default"},
              "flags": [
                {
                  "key": "bool-flag",
                  "name": "bool-flag",
                  "type": "BOOLEAN_FLAG_TYPE",
                  "enabled": true,
                  "rollouts": [
                    {"threshold": {"percentage": 100.0, "value": true}}
                  ]
                },
                {
                  "key": "string-flag",
                  "name": "string-flag",
                  "type": "VARIANT_FLAG_TYPE",
                  "enabled": true,
                  "rules": [
                    {
                      "distributions": [{"variantKey": "greeting", "rollout": 100.0}],
                      "segments": [
                        {"key": "everyone", "matchType": "ALL_SEGMENT_MATCH_TYPE", "constraints": []}
                      ],
                      "segmentOperator": "OR_SEGMENT_OPERATOR"
                    }
                  ]
                }
              ]
            }
            """;

    private static final String BOOL_REQUEST = "{\"flag_key\":\"bool-flag\",\"entity_id\":\"user-1\"}";
    private static final String VARIANT_REQUEST = "{\"flag_key\":\"string-flag\",\"entity_id\":\"user-1\"}";

    private static final int ENGINES = 8;
    private static final int WARMUP = 20_000;
    private static final int EVALUATIONS = 200_000;
    private static final int LOAD_SECONDS = 5;

    @Test
    public void measureSingleEngine() {
        ObjectMapper mapper = new ObjectMapper();
        long heapBefore = heapUsed();

        List<FliptWasmEngine> engines = new ArrayList<>();
        long[] creationNanos = new long[ENGINES];
        for (int i = 0; i < ENGINES; i++) {
            long start = System.nanoTime();
            FliptWasmEngine engine = new FliptWasmEngine(mapper);
            engine.initialize("default", SNAPSHOT);
            creationNanos[i] = System.nanoTime() - start;
            engines.add(engine);
        }

        FliptWasmEngine engine = engines.get(0);
        System.out.println("=== Flipt WASM engine ===");
        System.out.printf("creation: first %.1f ms, rest median %.1f ms, rest max %.1f ms%n",
                millis(creationNanos[0]),
                millis(median(Arrays.copyOfRange(creationNanos, 1, ENGINES))),
                millis(max(Arrays.copyOfRange(creationNanos, 1, ENGINES))));

        for (int i = 0; i < WARMUP; i++) {
            engine.evaluateBoolean(BOOL_REQUEST);
            engine.evaluateVariant(VARIANT_REQUEST);
        }

        measureEvaluation("boolean evaluation", () -> engine.evaluateBoolean(BOOL_REQUEST));
        measureEvaluation("variant evaluation", () -> engine.evaluateVariant(VARIANT_REQUEST));

        long heapAfter = heapUsed();

        System.out.println("WASM memory per instance: " + pagesMB(engine.memoryPages()));
        System.out.printf("JVM heap per instance: %.1f MB (%d instances, %.1f MB total)%n",
                mb(heapAfter - heapBefore) / ENGINES, ENGINES, mb(heapAfter - heapBefore));

        for (FliptWasmEngine each : engines) {
            each.destroy();
        }
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
        FliptWasmEnginePool pool = new FliptWasmEnginePool(minSize, "default", SNAPSHOT, new ObjectMapper());
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
                        pool.evaluateBoolean(BOOL_REQUEST);
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

        System.out.printf("=== Flipt pool, %d threads, minimum %d, maximum %d ===%n",
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
