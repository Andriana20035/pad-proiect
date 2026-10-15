package md.lab2.demo;

import md.lab2.client.ConsumerClient;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.consumer.SoftCrashOnceInjector;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.rpc.v1.PublishReply;
import md.lab2.rpc.v1.StatsReply;
import md.lab2.server.BrokerServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class DemoMain {
    private DemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Path demoDir = Files.createTempDirectory("lab2-rpc-demo-");
        Path brokerDb = demoDir.resolve("broker.db");
        AppConfig base = AppConfig.fromSystemProperties()
                .withDatabase(brokerDb);

        AppConfig configA = base.withPortAndNode(0, "broker-a");
        AppConfig configB = base.withPortAndNode(0, "broker-b");

        try (BrokerServer brokerA = new BrokerServer(configA);
             BrokerServer brokerB = new BrokerServer(configB)) {
            brokerA.start();
            brokerB.start();
            List<Endpoint> endpoints = List.of(
                    new Endpoint("localhost", brokerA.port()),
                    new Endpoint("localhost", brokerB.port()));

            try (ProducerClient admin = new ProducerClient(
                    endpoints, base.unaryDeadlineMs())) {
                admin.createTopic("orders.v1");
            }

            ConsumerLocalStore inventoryStore = new ConsumerLocalStore(
                    demoDir.resolve("inventory.db"));
            ConsumerLocalStore auditStore = new ConsumerLocalStore(
                    demoDir.resolve("audit.db"));

            ConsumerClient inventory = new ConsumerClient(
                    "inventory-service",
                    "orders.v1",
                    endpoints,
                    base.unaryDeadlineMs(),
                    200,
                    new PersistentConsumerHandler(
                            "inventory-service",
                            inventoryStore,
                            message -> message.getKey()),
                    new SoftCrashOnceInjector());
            ConsumerClient audit = new ConsumerClient(
                    "audit-service",
                    "orders.v1",
                    endpoints,
                    base.unaryDeadlineMs(),
                    200,
                    new PersistentConsumerHandler(
                            "audit-service",
                            auditStore,
                            message -> "audit:" + message.getId()),
                    ConsumerFailureInjector.NONE);

            Thread inventoryThread = new Thread(inventory, "inventory-rpc-consumer");
            Thread auditThread = new Thread(audit, "audit-rpc-consumer");
            try {
                inventoryThread.start();
                auditThread.start();

                waitFor(() -> {
                    try (ProducerClient check = new ProducerClient(
                            endpoints, base.unaryDeadlineMs())) {
                        return check.stats("orders.v1", "")
                                .getActiveSubscriptions() == 2;
                    }
                }, Duration.ofSeconds(5), "consumers did not subscribe");

                PublishReply first;
                PublishReply second;
                try (ProducerClient producer = new ProducerClient(
                        endpoints, base.unaryDeadlineMs())) {
                    first = producer.publishOrderCreated(
                            "orders.v1",
                            "ORD-2001",
                            "{\"product\":\"Laptop\",\"quantity\":1}");
                    second = producer.publishOrderCreated(
                            "orders.v1",
                            "ORD-2002",
                            "{\"product\":\"Monitor\",\"quantity\":2}");
                }

                waitFor(() -> {
                    try (ProducerClient check = new ProducerClient(
                            endpoints, base.unaryDeadlineMs())) {
                        StatsReply stats = check.stats("orders.v1", "");
                        return stats.getCompleted() == 4;
                    }
                }, Duration.ofSeconds(12), "deliveries did not complete");

                try (ProducerClient check = new ProducerClient(
                        endpoints, base.unaryDeadlineMs())) {
                    StatsReply stats = check.stats("orders.v1", "");
                    if (first.getBrokerNode().equals(second.getBrokerNode())
                            || inventoryStore.effectCount("ORD-2001") != 1
                            || inventoryStore.effectCount("ORD-2002") != 1
                            || stats.getCompleted() != 4 || stats.getDlq() != 0) {
                        throw new IllegalStateException("Demo guarantees were not satisfied");
                    }
                    System.out.println("\n=== LAB 2 RESULT ===");
                    System.out.println("First publish broker  = " + first.getBrokerNode());
                    System.out.println("Second publish broker = " + second.getBrokerNode());
                    System.out.println("Inventory ORD-2001 effectCount = "
                            + inventoryStore.effectCount("ORD-2001"));
                    System.out.println("Inventory ORD-2002 effectCount = "
                            + inventoryStore.effectCount("ORD-2002"));
                    System.out.println("Completed deliveries = " + stats.getCompleted());
                    System.out.println("DLQ size = " + stats.getDlq());
                    System.out.println("Demo data = " + demoDir);
                    System.out.println("EXPECTED: different broker nodes, effects=1, completed=4, DLQ=0");
                }

            } finally {
                inventory.close();
                audit.close();
                inventoryThread.interrupt();
                auditThread.interrupt();
                inventoryThread.join(3000);
                auditThread.join(3000);
                if (inventoryThread.isAlive() || auditThread.isAlive()) {
                    throw new IllegalStateException("Demo consumer threads did not terminate");
                }
            }
        }
    }

    private static void waitFor(
            Condition condition,
            Duration timeout,
            String errorMessage) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(errorMessage);
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate() throws Exception;
    }
}
