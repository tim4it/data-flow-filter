package tim4it.login.eko;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import tim4it.login.eko.api.ImportHandler;
import tim4it.login.eko.api.QueryHandler;
import tim4it.login.eko.config.Config;
import tim4it.login.eko.db.CSVImportService;
import tim4it.login.eko.db.SQLiteDB;
import tim4it.login.eko.http.Http;
import tim4it.login.eko.http.HttpStatus;
import tim4it.login.eko.util.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Application bootstrap. Launches an HTTP server with endpoints for CSV import and telemetry querying.
 * <p>
 * Endpoints: POST /import  - Import a CSV telemetry file (raw body or multipart) POST /query   - Query telemetry data
 * with filters GET  /health  - Health check
 */
@Slf4j
final class Startup {

    final Config config;
    final SQLiteDB db;
    final CSVImportService importService;

    Startup(@NonNull Config config) {
        this.config = config;
        this.db = new SQLiteDB(config.databasePath());
        this.importService = new CSVImportService(this.db);
    }

    void run() {
        log.info("Starting data-flow-filter service...");

        migrateDatabase();

        try {
            var server = HttpServer.create(new InetSocketAddress(config.serverPort()), 0);

            server.createContext("/", this::handleRoot);
            server.createContext("/import", new ImportHandler(importService, config));
            server.createContext("/query", new QueryHandler(db, config));
            server.createContext("/health", this::handleHealth);
            server.createContext("/info", this::handleInfo);

            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(config.serverThreadPoolSize()));
            server.start();

            log.info("Server started on port {}", config.serverPort());
            log.info("Endpoints:");
            log.info("  POST /import  - Import CSV telemetry file");
            log.info("  POST /query   - Query telemetry data with filters");
            log.info("  GET  /health  - Health check");
            log.info("  GET  /info    - Configuration information");
        } catch (IOException e) {
            log.error("Failed to start server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    void migrateDatabase() {
        try {
            var flyway = Flyway.configure()
                .dataSource(db.getDbUrl(), null, null)
                .locations("classpath:db/migration")
                .load();

            flyway.migrate();
            log.info("Flyway migration completed successfully");
        } catch (Exception e) {
            log.error("Failed to run database migrations: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    void handleRoot(@NonNull HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange.getResponseHeaders());

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(HttpStatus.NO_CONTENT.getCode(), -1);
            return;
        }

        var body = JsonUtil.toJson(Map.of(
            "status", "error",
            "error", "Not found. Use /import or /query."
        ));
        sendJson(exchange, HttpStatus.NOT_FOUND, body);
    }

    void handleInfo(@NonNull HttpExchange exchange) throws IOException {
        sendJson(exchange, HttpStatus.OK, JsonUtil.toJson(config));
    }

    void handleHealth(@NonNull HttpExchange exchange) {
        try {
            var eventMachineCountPair = db.getEventMachineCount();
            var body = JsonUtil.toJson(Map.of(
                "status", "ok",
                "machines", eventMachineCountPair.second(),
                "events", eventMachineCountPair.first()
            ));
            sendJson(exchange, HttpStatus.OK, body);
        } catch (Exception e) {
            log.error("Health check failed", e);
            var body = JsonUtil.toJson(Map.of(
                "status", "error",
                "error", e.getMessage()
            ));
            try {
                sendJson(exchange, HttpStatus.INTERNAL_SERVER_ERROR, body);
            } catch (IOException ioException) {
                log.error("Failed to send error response", ioException);
            }
        }
    }

    void setCorsHeaders(@NonNull Headers headers) {
        headers.set(Http.ACCESS_CONTROL_ALLOW_ORIGIN, config.corsAllowOrigins());
        headers.set(Http.ACCESS_CONTROL_ALLOW_METHODS, config.corsAllowMethods());
        headers.set(Http.ACCESS_CONTROL_ALLOW_HEADERS, config.corsAllowHeaders());
    }

    void sendJson(@NonNull HttpExchange exchange, @NonNull HttpStatus status, @NonNull String body) throws IOException {
        exchange.getResponseHeaders().set(Http.CONTENT_TYPE, Http.APPLICATION_JSON);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status.getCode(), bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
