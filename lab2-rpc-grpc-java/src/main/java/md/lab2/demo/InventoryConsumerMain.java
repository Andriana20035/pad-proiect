package md.lab2.demo;

import md.lab2.client.ConsumerClient;
import md.lab2.config.AppConfig;
import md.lab2.consumer.ConsumerFailureInjector;
import md.lab2.consumer.ConsumerLocalStore;
import md.lab2.consumer.HardCrashOnceInjector;
import md.lab2.consumer.PersistentConsumerHandler;
import md.lab2.consumer.SoftCrashOnceInjector;

import java.nio.file.Path;

public final class InventoryConsumerMain {
    private InventoryConsumerMain() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromSystemProperties();
        String topic = System.getProperty("lab2.topic", "orders.v1");
        Path localDb = Path.of(System.getProperty(
                "lab2.inventoryDb",
                "data/inventory-rpc.db"));

        ConsumerLocalStore localStore = new ConsumerLocalStore(localDb);
        PersistentConsumerHandler handler = new PersistentConsumerHandler(
                "inventory-service",
                localStore,
                message -> message.getKey());

        ConsumerFailureInjector failureInjector = chooseFailureInjector();
        try (ConsumerClient consumer = new ConsumerClient(
                "inventory-service",
                topic,
                config.endpoints(),
                config.unaryDeadlineMs(),
                300,
                handler,
                failureInjector)) {
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));
            consumer.run();
        }
    }

    private static ConsumerFailureInjector chooseFailureInjector() {
        if (Boolean.getBoolean("lab2.hardCrashOnce")) {
            Path marker = Path.of(System.getProperty(
                    "lab2.crashMarker",
                    "data/inventory-hard-crash.marker"));
            return new HardCrashOnceInjector(marker);
        }
        if (Boolean.getBoolean("lab2.softCrashOnce")) {
            return new SoftCrashOnceInjector();
        }
        return ConsumerFailureInjector.NONE;
    }
}
