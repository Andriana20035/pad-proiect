package md.lab.integrationservice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/** A single budget covers connecting and awaiting the response. */
public final class Network {
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "network-deadline");
                thread.setDaemon(true);
                return thread;
            });
    public record Deadline(java.util.concurrent.ScheduledFuture<?> task) implements AutoCloseable {
        @Override public void close() { task.cancel(false); }
    }
    private Network() {}
    public static Deadline deadline(Socket socket, long deadline) {
        return new Deadline(TIMER.schedule(() -> {
            try { socket.close(); } catch (IOException ignored) { }
        }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
    }
    public static Socket connect(String host, int port) throws IOException {
        return connect(host, port, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AppConfig.number("network.deadlineMs")));
    }
    public static Socket connect(String host, int port, long deadline) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), remaining(deadline));
            socket.setSoTimeout(remaining(deadline));
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }
    public static int remaining(long deadline) throws java.net.SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.net.SocketTimeoutException("Request deadline exceeded");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
