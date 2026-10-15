package md.lab2.consumer;

import md.lab2.rpc.v1.Envelope;

@FunctionalInterface
public interface ConsumerHandler {
    ProcessResult process(Envelope message);
}
