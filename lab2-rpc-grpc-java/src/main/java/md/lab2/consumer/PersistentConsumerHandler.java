package md.lab2.consumer;

import md.lab2.logging.CorrelatedLog;
import md.lab2.rpc.v1.Envelope;

import java.util.function.Function;

public final class PersistentConsumerHandler implements ConsumerHandler {
    private final String component;
    private final ConsumerLocalStore store;
    private final Function<Envelope, String> effectKey;

    public PersistentConsumerHandler(
            String component,
            ConsumerLocalStore store,
            Function<Envelope, String> effectKey) {
        this.component = component;
        this.store = store;
        this.effectKey = effectKey;
    }

    @Override
    public ProcessResult process(Envelope message) {
        String key = effectKey.apply(message);
        boolean applied = store.applyOnce(message, key);
        if (applied) {
            CorrelatedLog.event(
                    component,
                    "LOCAL_EFFECT_PERSISTED",
                    message,
                    "effectKey=" + key + " effectCount=" + store.effectCount(key));
            return new ProcessResult(true, "local effect persisted");
        }
        CorrelatedLog.event(
                component,
                "DEDUP_SKIP",
                message,
                "effectKey=" + key + " already persisted");
        return new ProcessResult(false, "duplicate skipped");
    }
}
