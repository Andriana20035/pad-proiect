package md.lab2.server;

import md.lab2.config.AppConfig;

public final class BrokerMain {
    private BrokerMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromSystemProperties();
        BrokerServer server = new BrokerServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Press Ctrl+C to stop the broker.");
        server.blockUntilShutdown();
    }
}
