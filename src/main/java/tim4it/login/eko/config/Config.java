package tim4it.login.eko.config;

import lombok.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Application configuration loaded from properties files.
 * <p>
 * Precedence (lowest to highest): 1. classpath:application.properties  (defaults baked into the jar) 2.
 * config/application.properties     (user override next to the executable) 3. System properties                 (e.g.
 * -Dserver.port=9090)
 */
public record Config(
    int serverPort,
    int serverThreadPoolSize,
    String databasePath,
    String corsAllowOrigins,
    String corsAllowMethods,
    String corsAllowHeaders
) {

    private static final Path USER_CONFIG = Path.of("config", "application.properties");

    /**
     * Load configuration from defaults, optional user file, and system properties.
     */
    public static Config load() {
        var props = new Properties();

        // 1. Defaults from classpath
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            // Fall through — properties file not found, use nulls below
        }

        // 2. User override (optional file next to the executable)
        if (Files.exists(USER_CONFIG)) {
            try (InputStream is = Files.newInputStream(USER_CONFIG)) {
                props.load(is);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + USER_CONFIG, e);
            }
        }

        // 3. System properties override everything
        for (var entry : System.getProperties().stringPropertyNames()) {
            if (entry.startsWith("server.") || entry.startsWith("database.") || entry.startsWith("cors.")) {
                props.setProperty(entry, System.getProperty(entry));
            }
        }

        return parse(props);
    }

    static Config parse(@NonNull Properties props) {
        var port = parseUnsignedInt(props, "server.port", 8080);
        var poolSize = parseUnsignedInt(props, "server.thread-pool-size", 4);
        var dbPath = props.getProperty("database.path", "DB/data_flow-filter.db");
        var corsOrigins = props.getProperty("cors.allow-origins", "*");
        var corsMethods = props.getProperty("cors.allow-methods", "GET, POST, OPTIONS");
        var corsHeaders = props.getProperty("cors.allow-headers", "Content-Type");

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535, got: " + port);
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("server.thread-pool-size must be at least 1, got: " + poolSize);
        }

        return new Config(port, poolSize, dbPath, corsOrigins, corsMethods, corsHeaders);
    }

    private static int parseUnsignedInt(@NonNull Properties props, @NonNull String key, int fallback) {
        var raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseUnsignedInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + raw, e);
        }
    }
}
