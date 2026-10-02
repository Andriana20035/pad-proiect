package md.lab.failure;

import md.lab.orderservice.OrderProducer;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineTest {
    @Test void producerTimesOutWhenBrokerAcceptsConnectionButDoesNotRespond() throws Exception {
        String previous = System.getProperty("network.deadlineMs");
        System.setProperty("network.deadlineMs", "250");
        try (ServerSocket stalled = new ServerSocket(0)) {
            Thread peer = new Thread(() -> {
                try (Socket socket = stalled.accept()) {
                    while (socket.getInputStream().read() != -1) { /* never respond */ }
                } catch (IOException ignored) { }
            });
            peer.start();
            try {
                long start = System.nanoTime();
                assertThrows(IOException.class, () -> new OrderProducer("localhost", stalled.getLocalPort())
                        .sendOrderCreated("DEADLINE", "{\"product\":\"Laptop\",\"quantity\":1}"));
                assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
            } finally {
                stalled.close();
                peer.join(3000);
                assertFalse(peer.isAlive());
            }
        } finally {
            if (previous == null) System.clearProperty("network.deadlineMs");
            else System.setProperty("network.deadlineMs", previous);
        }
    }
}
