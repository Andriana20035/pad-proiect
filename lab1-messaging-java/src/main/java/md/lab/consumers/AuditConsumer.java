package md.lab.consumers;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.AppConfig;
import md.lab.integrationservice.LogUtil;
import java.nio.file.Path;

public class AuditConsumer extends AbstractSocketConsumer {
    private final DedupStore audit;
    public AuditConsumer(String host, int port) { this(host, port, AppConfig.storage("audit.json")); }
    public AuditConsumer(String host, int port, Path storage) {
        super(host, port, "audit-consumer", "orders");
        audit = new DedupStore(storage);
    }
    @Override protected void process(MessageEnvelope message) {
        LogUtil.log("audit-consumer", audit.apply(message) ? "AUDIT_WRITTEN" : "DEDUP_SKIP",
                message, "durable audit record");
    }
    public static void main(String[] args) {
        new AuditConsumer(AppConfig.value("broker.host"), AppConfig.number("broker.port")).run();
    }
}

