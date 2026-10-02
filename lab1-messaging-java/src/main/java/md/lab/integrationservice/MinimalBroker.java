package md.lab.integrationservice;

import md.lab.api.MessageEnvelope;
import md.lab.api.MessageValidator;
import java.nio.file.Path;
import java.util.*;

/** Serialized state transitions preserve publication order and durable in-flight deliveries. */
public class MinimalBroker {
    public record Subscription(String channel, List<Delivery> queue, Delivery inFlight) {}
    public record Snapshot(Map<String, Subscription> consumers, Map<String, MessageEnvelope> requests,
                           Map<String, String> routes, List<DeadLetter> deadLetters) {}
    private final Map<String, ConsumerState> consumers = new LinkedHashMap<>();
    private final Map<String, Delivery> inFlight = new LinkedHashMap<>();
    private final Map<String, MessageEnvelope> requests = new LinkedHashMap<>();
    private final Map<String, String> routes = new LinkedHashMap<>();
    private final List<DeadLetter> deadLetters = new ArrayList<>();
    private final int maxAttempts;
    private final Path storage;
    private boolean storageFailed;

    public MinimalBroker(int maxAttempts) { this(maxAttempts, null); }

    public MinimalBroker(int maxAttempts, Path storage) {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.storage = storage;
        Snapshot snapshot = JsonFileStore.read(storage, Snapshot.class,
                new Snapshot(Map.of(), Map.of(), Map.of(), List.of()));
        requests.putAll(snapshot.requests());
        routes.putAll(snapshot.routes());
        deadLetters.addAll(snapshot.deadLetters());
        snapshot.consumers().forEach((id, saved) -> {
            ConsumerState state = new ConsumerState(id, saved.channel());
            state.queue.addAll(saved.queue());
            consumers.put(id, state);
            if (saved.inFlight() != null) {
                Delivery delivery = saved.inFlight();
                if (delivery.attempt() < maxAttempts) state.queue.addFirst(delivery.nextAttempt());
                else deadLetters.add(new DeadLetter(id, delivery.message(), "broker restart before ACK", delivery.attempt()));
                LogUtil.log("broker", "RECOVERED", delivery.message(), "consumer=" + id);
            }
        });
        save();
    }

    public synchronized void registerConsumer(String consumerId, String channel) {
        healthy();
        if (consumerId == null || consumerId.isBlank() || !"orders".equals(channel))
            throw new IllegalArgumentException("Consumer ID required; supported channel: orders");
        ConsumerState existing = consumers.get(consumerId);
        if (existing != null && !existing.channel.equals(channel)) throw new IllegalArgumentException("Channel conflict");
        if (existing == null) {
            consumers.put(consumerId, new ConsumerState(consumerId, channel));
            save();
        }
    }

    public int publish(String channel, MessageEnvelope message) { return publish(channel, message, null); }

    public synchronized int publish(String channel, MessageEnvelope message, String targetConsumerId) {
        healthy();
        MessageValidator.validate(message);
        if (!"orders".equals(channel)) throw new IllegalArgumentException("Unsupported channel");
        List<String> recipients = new ArrayList<>();
        for (ConsumerState state : consumers.values()) {
            if (state.channel.equals(channel) && (targetConsumerId == null || state.consumerId.equals(targetConsumerId)))
                recipients.add(state.consumerId);
        }
        if (recipients.isEmpty()) throw new IllegalArgumentException("Unknown destination");
        String route = targetConsumerId == null ? "*" : targetConsumerId;
        MessageEnvelope previous = requests.get(message.key());
        if (previous != null) {
            if (!MessageValidator.sameRequest(previous, message) || !route.equals(routes.get(message.key())))
                throw new IllegalArgumentException("Order ID already used with different content or destination");
            LogUtil.log("broker", "PUBLISH_DEDUP", previous, "request already registered");
            return recipients.size();
        }
        if (requests.values().stream().anyMatch(saved -> saved.id().equals(message.id())))
            throw new IllegalArgumentException("Message ID already belongs to another request");
        requests.put(message.key(), message);
        routes.put(message.key(), route);
        for (String id : recipients) consumers.get(id).queue.addLast(new Delivery(message, 1));
        save(); // PUBLISHED is sent only after this commit.
        for (String id : recipients) LogUtil.log("broker", "QUEUED", message, "consumer=" + id);
        LogUtil.log("broker", "ACCEPTED", message, "channel=" + channel + " recipients=" + recipients);
        notifyAll();
        return recipients.size();
    }

    public synchronized Delivery poll(String consumerId, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            healthy();
            ConsumerState state = requiredState(consumerId);
            if (!inFlight.containsKey(consumerId) && !state.queue.isEmpty()) {
                Delivery delivery = state.queue.removeFirst();
                inFlight.put(consumerId, delivery);
                save();
                return delivery;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    public Delivery take(String consumerId) throws InterruptedException {
        Delivery delivery;
        while ((delivery = poll(consumerId, 1000)) == null) { /* interruptible wait */ }
        return delivery;
    }

    public synchronized void ack(String consumerId, Delivery delivery) {
        healthy();
        checkInFlight(consumerId, delivery);
        inFlight.remove(consumerId);
        save();
        LogUtil.log("broker", "ACK", delivery.message(), "consumer=" + consumerId + " attempt=" + delivery.attempt());
        notifyAll();
    }

    public void retryOrDlq(String consumerId, Delivery delivery, String reason) {
        retryOrDlq(consumerId, delivery, reason, true);
    }

    public synchronized void retryOrDlq(String consumerId, Delivery delivery, String reason, boolean retryable) {
        healthy();
        checkInFlight(consumerId, delivery);
        inFlight.remove(consumerId);
        if (retryable && delivery.attempt() < maxAttempts) {
            requiredState(consumerId).queue.addFirst(delivery.nextAttempt());
            save();
            LogUtil.log("broker", "RETRY", delivery.message(),
                    "consumer=" + consumerId + " nextAttempt=" + (delivery.attempt() + 1) + " reason=" + reason);
        } else {
            deadLetters.add(new DeadLetter(consumerId, delivery.message(), reason, delivery.attempt()));
            save();
            LogUtil.log("broker", "DLQ", delivery.message(), "consumer=" + consumerId + " reason=" + reason);
        }
        notifyAll();
    }

    public synchronized int pendingCount(String id) { return requiredState(id).queue.size(); }
    public synchronized boolean isIdle() {
        return inFlight.isEmpty() && consumers.values().stream().allMatch(state -> state.queue.isEmpty());
    }
    public synchronized List<DeadLetter> deadLetters() { return List.copyOf(deadLetters); }

    private ConsumerState requiredState(String id) {
        ConsumerState state = consumers.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown consumer: " + id);
        return state;
    }
    private void checkInFlight(String id, Delivery delivery) {
        if (!delivery.equals(inFlight.get(id))) throw new IllegalArgumentException("Delivery is not in flight");
    }
    private void healthy() {
        if (storageFailed) throw new IllegalStateException("Storage failed; restart broker after fixing disk");
    }
    private void save() {
        Map<String, Subscription> saved = new LinkedHashMap<>();
        consumers.forEach((id, state) -> saved.put(id,
                new Subscription(state.channel, new ArrayList<>(state.queue), inFlight.get(id))));
        try {
            JsonFileStore.write(storage, new Snapshot(saved, requests, routes, deadLetters));
        } catch (RuntimeException e) {
            storageFailed = true; // Never acknowledge additional requests after a failed disk commit.
            throw e;
        }
    }
}

