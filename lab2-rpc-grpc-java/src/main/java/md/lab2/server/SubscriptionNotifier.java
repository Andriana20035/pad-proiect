package md.lab2.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wakes subscription workers when broker state relevant to deliveries changes.
 *
 * A bounded timeout is also used so a broker can observe changes written
 * by another broker process that shares the same SQLite database.
 */
public final class SubscriptionNotifier {
    private final ConcurrentMap<String, TopicSignal> topics =
            new ConcurrentHashMap<>();

    public long snapshot(String topic) {
        TopicSignal signal = signalFor(topic);
        signal.lock.lock();
        try {
            return signal.version;
        } finally {
            signal.lock.unlock();
        }
    }

    public long awaitChange(
            String topic,
            long observedVersion,
            long timeoutMs) throws InterruptedException {

        TopicSignal signal = signalFor(topic);
        signal.lock.lockInterruptibly();

        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, timeoutMs));

            while (signal.version == observedVersion && remaining > 0L) {
                remaining = signal.changed.awaitNanos(remaining);
            }

            return signal.version;
        } finally {
            signal.lock.unlock();
        }
    }

    public void signal(String topic) {
        signalFor(topic).signal();
    }

    public void signalAll() {
        for (TopicSignal signal : topics.values()) {
            signal.signal();
        }
    }

    private TopicSignal signalFor(String topic) {
        return topics.computeIfAbsent(
                topic,
                ignored -> new TopicSignal()
        );
    }

    private static final class TopicSignal {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private long version;

        private void signal() {
            lock.lock();

            try {
                version++;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}