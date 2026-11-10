package md.lab3;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/** Rolling nearest-rank percentiles, measured for completed client-facing /api requests. */
final class LatencyMetrics {
    private final long[] samples = new long[50_000];
    private long count = 0;
    final LongAdder cacheHit = new LongAdder();
    final LongAdder cacheMiss = new LongAdder();
    final LongAdder cacheStale = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder retries = new LongAdder();
    final LongAdder upstreamFailures = new LongAdder();
    final LongAdder successful = new LongAdder();

    synchronized void recordMicros(long us) {
        samples[(int) (count % samples.length)] = Math.max(0, us);
        count++;
    }
    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int index = Math.max(0, (int)Math.ceil(sorted.length * p) - 1);
        return sorted[index];
    }
    synchronized String snapshot(String backendJson, int cacheSize, int inflight) {
        int n = (int)Math.min(count, samples.length);
        long[] copy = Arrays.copyOf(samples, n);
        Arrays.sort(copy);
        return "{\"requests\":" + count + ",\"windowSize\":" + n
          + ",\"p50Ms\":" + String.format(java.util.Locale.ROOT,"%.3f",percentile(copy,.50)/1000.0)
          + ",\"p95Ms\":" + String.format(java.util.Locale.ROOT,"%.3f",percentile(copy,.95)/1000.0)
          + ",\"p99Ms\":" + String.format(java.util.Locale.ROOT,"%.3f",percentile(copy,.99)/1000.0)
          + ",\"cacheHit\":" + cacheHit.sum() + ",\"cacheMiss\":" + cacheMiss.sum()
          + ",\"cacheStale\":" + cacheStale.sum() + ",\"rejected429\":" + rejected.sum()
          + ",\"retries\":" + retries.sum() + ",\"upstreamFailures\":" + upstreamFailures.sum()
          + ",\"success2xx\":" + successful.sum() + ",\"cacheSize\":" + cacheSize
          + ",\"inflight\":" + inflight + ",\"backends\":" + backendJson + "}";
    }
}
