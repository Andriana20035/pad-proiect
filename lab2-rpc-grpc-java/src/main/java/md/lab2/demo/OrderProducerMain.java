package md.lab2.demo;

import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;
import md.lab2.rpc.v1.PublishReply;

public final class OrderProducerMain {
    private OrderProducerMain() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromSystemProperties();
        String topic = System.getProperty("lab2.topic", "orders.v1");
        String orderId = args.length > 0 ? args[0] : "ORD-2001";
        String payload = args.length > 1
                ? args[1]
                : "{\"product\":\"Laptop\",\"quantity\":1}";

        try (ProducerClient producer = new ProducerClient(
                config.endpoints(),
                config.unaryDeadlineMs())) {
            producer.createTopic(topic);
            PublishReply reply = producer.publishOrderCreated(
                    topic,
                    orderId,
                    payload);
            System.out.printf(
                    "PUBLISH RESULT: accepted=%s duplicate=%s routedTo=%d broker=%s messageId=%s%n",
                    reply.getAccepted(),
                    reply.getDuplicate(),
                    reply.getRoutedCount(),
                    reply.getBrokerNode(),
                    reply.getMessageId());
        }
    }
}
