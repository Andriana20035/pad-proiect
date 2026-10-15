package md.lab2.config;

public record Endpoint(String host, int port) {
    public Endpoint {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Endpoint host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid endpoint port: " + port);
        }
    }

    public static Endpoint parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Endpoint is empty");
        }
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Expected host:port, got: " + value);
        }
        String host = value.substring(0, separator).trim();
        int port = Integer.parseInt(value.substring(separator + 1).trim());
        return new Endpoint(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
