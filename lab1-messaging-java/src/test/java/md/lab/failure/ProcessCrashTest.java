package md.lab.failure;

import md.lab.api.MessageEnvelope;
import md.lab.consumers.InventoryHandler;
import md.lab.integrationservice.*;
import md.lab.orderservice.OrderProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProcessCrashTest {
    @TempDir Path directory;
    private Process launch(String consumer, int port, boolean crash, Path log) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dapp.config=" + Path.of("deployment/application.properties").toAbsolutePath(),
                "-Dbroker.port=" + port, "-Dbroker.host=localhost", "-Dstorage.directory=" + directory,
                "-cp", System.getProperty("java.class.path"), "md.lab.consumers." + consumer));
        if (crash) command.add("--crash-process");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    @Test void actualProcessCrashAfterEffectBeforeAckThenRestartDoesNotRepeatEffect() throws Exception {
        MinimalBroker broker = new MinimalBroker(3, directory.resolve("broker.json"));
        broker.registerConsumer("inventory-consumer", "orders");
        broker.registerConsumer("audit-consumer", "orders");
        Process first = null, second = null, audit = null;
        try (SocketBrokerServer server = new SocketBrokerServer(0, broker)) {
            server.start();
            try {
                first = launch("InventoryConsumer", server.port(), true, directory.resolve("crash.log"));
                audit = launch("AuditConsumer", server.port(), false, directory.resolve("audit.log"));
                OrderProducer producer = new OrderProducer("localhost", server.port());
                MessageEnvelope message = producer.sendOrderCreated("CRASH", "{\"product\":\"Laptop\",\"quantity\":1}");
                assertTrue(first.waitFor(15, TimeUnit.SECONDS), "Crash process must terminate");
                assertEquals(23, first.exitValue());
                second = launch("InventoryConsumer", server.port(), false, directory.resolve("restarted.log"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!broker.isIdle() && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(broker.isIdle(), "Both consumers must ACK");
                assertTrue(broker.deadLetters().isEmpty());
                assertEquals(1, new InventoryHandler(false, directory.resolve("inventory.json")).effectCount("CRASH"));
                producer.sendOrderCreated("CRASH", "{\"product\":\"Laptop\",\"quantity\":1}");
                assertTrue(broker.isIdle());
                String log = Files.readString(directory.resolve("restarted.log"));
                assertTrue(log.contains("DEDUP_SKIP"));
                assertTrue(log.contains(message.id()));
                assertTrue(log.contains(message.correlationId()));
                assertTrue(Files.readString(directory.resolve("audit.log")).contains(message.correlationId()));
            } finally {
                for (Process process : new Process[]{first, second, audit}) {
                    if (process != null) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }
}
