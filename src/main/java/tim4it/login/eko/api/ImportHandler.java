package tim4it.login.eko.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.NonNull;
import tim4it.login.eko.config.Config;
import tim4it.login.eko.db.CSVImportService;
import tim4it.login.eko.http.Http;
import tim4it.login.eko.http.HttpStatus;
import tim4it.login.eko.util.Helper;
import tim4it.login.eko.util.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTTP handler for POST /import endpoint. Accepts CSV data via raw body (text/csv) or multipart form upload.
 */
public class ImportHandler implements HttpHandler {

    final CSVImportService importService;
    final Config config;

    public ImportHandler(@NonNull CSVImportService importService, @NonNull Config config) {
        this.importService = importService;
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Helper.sendError(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed. Use POST.", config);
            return;
        }

        try {
            var contentType = exchange.getRequestHeaders().getFirst(Http.CONTENT_TYPE);
            var body = (contentType != null && contentType.contains("multipart/form-data")) ?
                parseMultipartBody(exchange.getRequestBody(), contentType) :
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (body.isBlank()) {
                Helper.sendError(exchange, HttpStatus.BAD_REQUEST, "Request body is empty", config);
                return;
            }

            var stats = importService.importCSV(body);

            var response = JsonUtil.toJson(Map.of(
                "status", "ok",
                "importedRows", stats.importedRows(),
                "errors", stats.errors(),
                "metricCount", stats.metricCount()
            ));

            Helper.sendJson(exchange, HttpStatus.OK, response, config);
        } catch (IllegalArgumentException e) {
            Helper.sendError(exchange, HttpStatus.BAD_REQUEST, e.getMessage(), config);
        } catch (SQLException e) {
            Helper.sendError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Database error: " + e.getMessage(), config);
        } catch (Exception e) {
            Helper.sendError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Import failed: " + e.getMessage(), config);
        }
    }

    /**
     * Parse multipart form data to extract the CSV file content.
     */
    private String parseMultipartBody(InputStream inputStream, String contentType) throws IOException {
        var boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("Cannot parse multipart: missing boundary");
        }

        var body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        var parts = body.split(Pattern.quote("--" + boundary));

        for (var part : parts) {
            if (part.isBlank() || "--".equals(part.trim())) {
                continue;
            }

            var headerEnd = part.indexOf("\r\n\r\n");
            var separatorLength = 4;
            if (headerEnd == -1) {
                headerEnd = part.indexOf("\n\n");
                separatorLength = 2;
            }
            if (headerEnd == -1) {
                continue;
            }

            var headers = part.substring(0, headerEnd);
            if (headers.contains("filename=")) {
                var content = part.substring(headerEnd + separatorLength);
                return content.replaceAll("\\r?\\n$", "");
            }
        }

        throw new IllegalArgumentException("No file found in multipart data");
    }

    /**
     * Extract boundary parameter from Content-Type header.
     */
    private String extractBoundary(String contentType) {
        var parts = contentType.split(";");
        for (var part : parts) {
            var trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                var value = trimmed.substring("boundary=".length()).trim();
                // Strip surrounding quotes if present, e.g. boundary="----XYZ"
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
}
