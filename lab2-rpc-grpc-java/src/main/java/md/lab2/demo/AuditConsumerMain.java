package md.lab2.demo;

import md.lab2.client.ConsumerClient;
import md.lab2.config.AppConfig;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.PersistentConsumerHandler;

import java.nio.file.Path;

public final class AuditConsumerMain {
    private AuditConsumerMain() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromSystemProperties();
        String topic = System.getProperty("lab2.topic", "orders.v1");
        Path localDb = Path.of(System.getProperty(
                "lab2.auditDb",
                "data/audit-rpc.db"));

        ConsumerLocalStore localStore = new ConsumerLocalStore(localDb);
        PersistentConsumerHandler handler = new PersistentConsumerHandler(
                "audit-service",
                localStore,
                message -> "audit:" + message.getId());

        try (ConsumerClient consumer = new ConsumerClient(
                "audit-service",
                topic,
                config.endpoints(),
                config.unaryDeadlineMs(),
                300,
                handler,
                ConsumerFailureInjector.NONE)) {
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::stop));
            consumer.run();
        }
    }
}
