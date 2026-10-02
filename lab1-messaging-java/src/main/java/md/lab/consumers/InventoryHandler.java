package md.lab.consumers;

import md.lab.api.MessageEnvelope;
import md.lab.integrationservice.LogUtil;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class InventoryHandler {
    private final DedupStore effects;
    private final AtomicBoolean crashOnce;
    public InventoryHandler(boolean simulateCrashOnce) { this(simulateCrashOnce, null); }
    public InventoryHandler(boolean simulateCrashOnce, Path storage) {
        effects = new DedupStore(storage);
        crashOnce = new AtomicBoolean(simulateCrashOnce);
    }
    public void handle(MessageEnvelope message) {
        if (!effects.apply(message)) {
            LogUtil.log("inventory-consumer", "DEDUP_SKIP", message, "durable effect already exists");
            return;
        }
        LogUtil.log("inventory-consumer", "LOCAL_EFFECT_DONE", message,
                "order=" + message.key() + " effectCount=1");
        if (crashOnce.compareAndSet(true, false))
            throw new SimulatedCrashBeforeAckException("after durable local effect, before ACK");
    }
    public int effectCount(String orderId) { return effects.effectCount(orderId); }
    public int dedupCount() { return effects.size(); }
}

