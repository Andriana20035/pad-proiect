package md.lab2.server;

import md.lab2.rpc.v1.PublishRequest;
import md.lab2.store.PublishOutcome;

@FunctionalInterface
public interface PublishFailureInjector {
    PublishFailureInjector NONE = (request, outcome) -> {
    };

    void afterPersist(PublishRequest request, PublishOutcome outcome);
}
