package md.lab2.unit;

import md.lab2.client.RoundRobinEndpointPool;
import md.lab2.config.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinEndpointPoolTest {
    @Test
    void rotatesEndpointsFairly() {
        Endpoint a = new Endpoint("localhost", 50051);
        Endpoint b = new Endpoint("localhost", 50052);
        RoundRobinEndpointPool pool = new RoundRobinEndpointPool(List.of(a, b));

        assertEquals(a, pool.next());
        assertEquals(b, pool.next());
        assertEquals(a, pool.next());
        assertEquals(b, pool.next());
    }
}
