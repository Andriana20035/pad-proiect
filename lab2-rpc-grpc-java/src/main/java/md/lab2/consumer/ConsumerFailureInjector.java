package md.lab2.consumer;

import md.lab2.rpc.v1.Delivery;

@FunctionalInterface
public interface ConsumerFailureInjector {
    ConsumerFailureInjector NONE = (delivery, result) -> {
    };

    void afterLocalPersist(Delivery delivery, ProcessResult result);
}
