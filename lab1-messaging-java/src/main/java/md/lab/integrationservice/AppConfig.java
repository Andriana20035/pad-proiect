package md.lab.integrationservice;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** External configuration; -Dapp.config selects another properties file. */
public final class AppConfig {
    private static final Properties VALUES = load();
    private AppConfig() {}

    private static Properties load() {
        Properties values = new Properties();
        Path file = Path.of(System.getProperty("app.config", "deployment/application.properties"));
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read configuration: " + file, e);
        }
    }

    public static String value(String key) {
        String value = System.getProperty(key, VALUES.getProperty(key));
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }

    public static int number(String key) {
        int value = Integer.parseInt(value(key));
        if (value <= 0) throw new IllegalArgumentException("Configuration must be positive: " + key);
        return value;
    }

    public static Path storage(String file) {
        return Path.of(value("storage.directory")).resolve(file);
    }
}
