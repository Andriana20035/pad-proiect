package md.lab2.integration;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import md.lab2.TestSupport;
import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.config.Endpoint;
import md.lab2.server.BrokerServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicRoutingTest {
    @TempDir
    Path tempDir;

    @Test
    void publishToUnknownTopicIsRejected() throws Exception {
        AppConfig config = TestSupport.config(tempDir.resolve("broker.db"), "broker-test");
        try (BrokerServer server = new BrokerServer(config)) {
            server.start();
            try (ProducerClient producer = new ProducerClient(
                    List.of(new Endpoint("localhost", server.port())),
                    1000)) {
                StatusRuntimeException error = assertThrows(
                        StatusRuntimeException.class,
                        () -> producer.publishOrderCreated(
                                "orders.v999",
                                "ORD-BAD-TOPIC",
                                "{}"));
                assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
            }
        }
    }
}
