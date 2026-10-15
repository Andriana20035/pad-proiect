package md.lab2.store;

public record StatsSnapshot(
        long messages,
        long pending,
        long inFlight,
        long completed,
        long dlq,
        long activeSubscriptions) {
}
