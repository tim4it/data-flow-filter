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
import tim4it.login.eko.util.Helper;
import tim4it.login.eko.util.JsonUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
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
        Helper.sendError(exchange, HttpStatus.NOT_FOUND, "Not found. Use /import or /query.", config, false);
    }

    void handleInfo(@NonNull HttpExchange exchange) throws IOException {
        Helper.sendJson(exchange, HttpStatus.OK, JsonUtil.toJson(config), config, false);
    }

    void handleHealth(@NonNull HttpExchange exchange) {
        try {
            var eventMachineCountPair = db.getEventMachineCount();
            var body = JsonUtil.toJson(Map.of(
                "status", "ok",
                "machines", eventMachineCountPair.second(),
                "events", eventMachineCountPair.first()
            ));
            Helper.sendJson(exchange, HttpStatus.OK, body, config, false);
        } catch (Exception e) {
            log.error("Health check failed", e);
            try {
                Helper.sendError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), config, false);
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
}
