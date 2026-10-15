package md.lab2.unit;

import md.lab2.store.BackoffPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackoffPolicyTest {
    @Test
    void exponentialBackoffIsBounded() {
        BackoffPolicy policy = new BackoffPolicy(100, 500);
        assertEquals(100, policy.delayMillis(1));
        assertEquals(200, policy.delayMillis(2));
        assertEquals(400, policy.delayMillis(3));
        assertEquals(500, policy.delayMillis(4));
        assertEquals(500, policy.delayMillis(8));
    }
}
