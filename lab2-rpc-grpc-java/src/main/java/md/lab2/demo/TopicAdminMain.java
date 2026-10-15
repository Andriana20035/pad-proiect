package md.lab2.demo;

import md.lab2.client.ProducerClient;
import md.lab2.config.AppConfig;

public final class TopicAdminMain {
    private TopicAdminMain() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromSystemProperties();
        String topic = args.length > 0
                ? args[0]
                : System.getProperty("lab2.topic", "orders.v1");
        try (ProducerClient client = new ProducerClient(
                config.endpoints(),
                config.unaryDeadlineMs())) {
            var reply = client.createTopic(topic);
            System.out.printf(
                    "TOPIC RESULT: name=%s created=%s broker=%s%n",
                    reply.getName(),
                    reply.getCreated(),
                    reply.getBrokerNode());
        }
    }
}
