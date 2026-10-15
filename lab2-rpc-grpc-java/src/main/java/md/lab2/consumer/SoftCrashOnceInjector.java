package md.lab2.consumer;

import md.lab2.rpc.v1.Delivery;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SoftCrashOnceInjector implements ConsumerFailureInjector {
    private final AtomicBoolean crash = new AtomicBoolean(true);

    @Override
    public void afterLocalPersist(Delivery delivery, ProcessResult result) {
        if (crash.compareAndSet(true, false)) {
            throw new SimulatedConsumerCrashException(
                    "Simulated consumer crash after local persistence and before final ACK");
        }
    }
}
