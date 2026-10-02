package md.lab;

import md.lab.consumers.AuditConsumer;
import md.lab.consumers.InventoryConsumer;
import md.lab.integrationservice.*;
import md.lab.orderservice.OrderProducer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Reload a new consumer from disk after the previous instance stops before ACK. */
public class DemoMain {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(AppConfig.storage("demo").toAbsolutePath().getParent());
        Path directory = Files.createTempDirectory(AppConfig.storage("demo").toAbsolutePath().getParent(), "demo-");
        MinimalBroker broker = new MinimalBroker(AppConfig.number("broker.maxAttempts"), directory.resolve("broker.json"));
        broker.registerConsumer("inventory-consumer", "orders");
        broker.registerConsumer("audit-consumer", "orders");
        String order = "ORD-" + UUID.randomUUID();
        try (SocketBrokerServer server = new SocketBrokerServer(AppConfig.number("broker.port"), broker)) {
            server.start();
            InventoryConsumer first = new InventoryConsumer(AppConfig.value("broker.host"), server.port(), true, directory.resolve("inventory.json"));
            AuditConsumer audit = new AuditConsumer(AppConfig.value("broker.host"), server.port(), directory.resolve("audit.json"));
            Thread firstThread = new Thread(first, "inventory-first");
            Thread auditThread = new Thread(audit, "audit");
            InventoryConsumer recovered = null;
            Thread recoveredThread = null;
            firstThread.start();
            auditThread.start();
            try {
                OrderProducer producer = new OrderProducer(AppConfig.value("broker.host"), server.port());
                String payload = "{\"product\":\"Laptop\",\"quantity\":1}";
                producer.sendOrderCreated(order, payload);
                firstThread.join(AppConfig.number("demo.timeoutMs"));
                if (firstThread.isAlive()) throw new IllegalStateException("Crash scenario timed out");
                recovered = new InventoryConsumer(AppConfig.value("broker.host"), server.port(), false, directory.resolve("inventory.json"));
                recoveredThread = new Thread(recovered, "inventory-recovered");
                recoveredThread.start();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(AppConfig.number("demo.timeoutMs"));
                while (!broker.isIdle() && System.nanoTime() < deadline) Thread.sleep(20);
                if (!broker.isIdle()) throw new IllegalStateException("ACK deadline exceeded");
                producer.sendOrderCreated(order, payload);
                if (recovered.handler().effectCount(order) != 1 || !broker.deadLetters().isEmpty())
                    throw new IllegalStateException("Demo invariant failed");
                System.out.println("VERIFIED: effectCount=1, DLQ=0, repeated request ignored.");
                System.out.println("Durable demo data: " + directory);
            } finally {
                first.stop();
                audit.stop();
                if (recovered != null) recovered.stop();
                firstThread.join(AppConfig.number("network.deadlineMs"));
                auditThread.join(AppConfig.number("network.deadlineMs"));
                if (recoveredThread != null) recoveredThread.join(AppConfig.number("network.deadlineMs"));
            }
        }
    }
}
