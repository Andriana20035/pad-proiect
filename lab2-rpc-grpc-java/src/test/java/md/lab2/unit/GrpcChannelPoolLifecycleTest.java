package md.lab2.unit;

import md.lab2.client.GrpcChannelPool;
import md.lab2.config.Endpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcChannelPoolLifecycleTest {
    @Test
    void closedPoolCannotReopenChannelsDuringConsumerShutdown() {
        GrpcChannelPool pool = new GrpcChannelPool();
        Endpoint endpoint = new Endpoint("localhost", 1);
        var channel = pool.channel(endpoint);
        pool.close();
        try {
            assertTrue(channel.isTerminated());
            assertThrows(IllegalStateException.class, () -> pool.channel(endpoint));
        } finally {
            pool.close();
        }
    }
}
