package md.lab2.contract;

import io.grpc.MethodDescriptor;
import md.lab2.rpc.v1.AckStage;
import md.lab2.rpc.v1.MessagingServiceGrpc;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtoContractTest {
    @Test
    void canonicalAndDeliverableProtoAreIdentical() throws Exception {
        String source = Files.readString(Path.of("src/main/proto/messaging_rpc.proto"));
        String contract = Files.readString(Path.of("contracts/messaging-rpc-v1.proto"));
        assertEquals(source, contract);
    }

    @Test
    void subscribeIsServerStreamingAndUnsubscribeIsUnary() {
        var subscribe = MessagingServiceGrpc.getSubscribeMethod();
        var unsubscribe = MessagingServiceGrpc.getUnsubscribeMethod();

        assertNotNull(subscribe);
        assertNotNull(unsubscribe);

        assertEquals(
                MethodDescriptor.MethodType.SERVER_STREAMING,
                subscribe.getType()
        );

        assertEquals(
                MethodDescriptor.MethodType.UNARY,
                unsubscribe.getType()
        );
    }

    @Test
    void ackStagesExposeReceivedProcessedPersisted() {
        assertEquals(1, AckStage.RECEIVED.getNumber());
        assertEquals(2, AckStage.PROCESSED.getNumber());
        assertEquals(3, AckStage.PERSISTED.getNumber());
        assertTrue(AckStage.PERSISTED.getNumber() > AckStage.PROCESSED.getNumber());
    }
}
