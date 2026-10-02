package md.lab.integrationservice;

import java.util.concurrent.LinkedBlockingDeque;

class ConsumerState {
    final String consumerId;
    final String channel;
    final LinkedBlockingDeque<Delivery> queue = new LinkedBlockingDeque<>();

    ConsumerState(String consumerId, String channel) {
        this.consumerId = consumerId;
        this.channel = channel;
    }
}
