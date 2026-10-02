package md.lab.consumers;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.AppConfig;
import java.nio.file.Path;

public class InventoryConsumer extends AbstractSocketConsumer {
    private final InventoryHandler handler;
    public InventoryConsumer(String host, int port, boolean crash) {
        this(host, port, crash, AppConfig.storage("inventory.json"));
    }
    public InventoryConsumer(String host, int port, boolean crash, Path storage) {
        super(host, port, "inventory-consumer", "orders");
        handler = new InventoryHandler(crash, storage);
    }
    @Override protected void process(MessageEnvelope message) { handler.handle(message); }
    public InventoryHandler handler() { return handler; }
    public static void main(String[] args) {
        boolean crash = java.util.Arrays.asList(args).contains("--crash-process");
        InventoryConsumer consumer = new InventoryConsumer(AppConfig.value("broker.host"),
                AppConfig.number("broker.port"), crash);
        consumer.haltProcessOnCrash(crash);
        consumer.run();
    }
}

