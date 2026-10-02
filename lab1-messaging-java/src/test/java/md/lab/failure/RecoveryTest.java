package md.lab.failure;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @TempDir Path directory;
    private static final String PAYLOAD = "{\"product\":\"Laptop\",\"quantity\":1}";

    @Test void recoversInflightBeforeNextMessageAndKeepsAckAndRequestHistory() throws Exception {
        Path file = directory.resolve("broker.json");
        MinimalBroker first = new MinimalBroker(3, file);
        first.registerConsumer("c1", "orders");
        MessageEnvelope a = MessageEnvelope.orderCreated("A", PAYLOAD);
        MessageEnvelope b = MessageEnvelope.orderCreated("B", PAYLOAD);
        first.publish("orders", a);
        first.publish("orders", b);
        assertEquals(a, first.poll("c1", 100).message());
        MinimalBroker recovered = new MinimalBroker(3, file);
        Delivery retry = recovered.poll("c1", 100);
        assertEquals(a, retry.message());
        assertEquals(2, retry.attempt());
        recovered.ack("c1", retry);
        Delivery next = recovered.poll("c1", 100);
        assertEquals(b, next.message());
        recovered.ack("c1", next);
        MinimalBroker restartedAgain = new MinimalBroker(3, file);
        restartedAgain.publish("orders", MessageEnvelope.orderCreated("A", PAYLOAD));
        assertTrue(restartedAgain.isIdle());
    }

    @Test void permanentFailureSkipsRetriesAndDlqSurvivesRestart() throws Exception {
        Path file = directory.resolve("broker.json");
        MinimalBroker broker = new MinimalBroker(3, file);
        broker.registerConsumer("c1", "orders");
        broker.publish("orders", MessageEnvelope.orderCreated("A", PAYLOAD));
        broker.retryOrDlq("c1", broker.poll("c1", 100), "permanent failure", false);
        MinimalBroker restarted = new MinimalBroker(3, file);
        assertTrue(restarted.isIdle());
        assertEquals(1, restarted.deadLetters().size());
        assertEquals(1, restarted.deadLetters().get(0).attempts());
    }
}
