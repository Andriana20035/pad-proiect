package md.lab.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.api.MessageEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageContractTest {
    @Test
    void serializedMessageContainsMandatoryContractFields() throws Exception {
        MessageEnvelope message = MessageEnvelope.orderCreated("ORD-7", "{\"product\":\"Laptop\",\"quantity\":1}");
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(message));

        assertTrue(json.hasNonNull("id"));
        assertTrue(json.hasNonNull("timestamp"));
        assertTrue(json.hasNonNull("correlationId"));
        assertEquals("1.0", json.get("schemaVersion").asText());
        assertEquals("ORDER_CREATED", json.get("type").asText());
    }
}
