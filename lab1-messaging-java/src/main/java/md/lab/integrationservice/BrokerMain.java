package md.lab.integrationservice;

import md.lab.orderservice.OrderProducer;

public class BrokerMain {
    public static void main(String[] args) throws Exception {
        MinimalBroker broker = new MinimalBroker(AppConfig.number("broker.maxAttempts"), AppConfig.storage("broker.json"));
        broker.registerConsumer("inventory-consumer", "orders");
        broker.registerConsumer("audit-consumer", "orders");

        try (SocketBrokerServer server = new SocketBrokerServer(AppConfig.number("broker.port"), broker)) {
            server.start();
            if (java.util.Arrays.asList(args).contains("--headless")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { server.close(); } catch (java.io.IOException ignored) { }
                }));
                new java.util.concurrent.CountDownLatch(1).await();
                return;
            }
            MessageConsole.run(new OrderProducer(AppConfig.value("broker.host"), server.port()), broker);
        }
    }
}
