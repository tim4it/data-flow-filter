package tim4it.login.eko.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.NonNull;
import tim4it.login.eko.config.Config;
import tim4it.login.eko.db.CoreField;
import tim4it.login.eko.db.SQLiteDB;
import tim4it.login.eko.http.HttpStatus;
import tim4it.login.eko.model.Filter;
import tim4it.login.eko.util.Helper;
import tim4it.login.eko.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * HTTP handler for POST /query endpoint. Accepts a JSON array of filter conditions and returns matching telemetry
 * events.
 * <p>
 * Example request body: [ {"field": "GrainTankUnloading", "value": true}, {"field": "GroundSpeed", "operation":
 * "GreaterThan", "value": 4.5} ]
 */
public class QueryHandler implements HttpHandler {

    private final SQLiteDB db;
    private final Config config;

    public QueryHandler(@NonNull SQLiteDB db, @NonNull Config config) {
        this.db = db;
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Helper.sendError(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed. Use POST.", config, true);
            return;
        }
        try {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                Helper.sendError(exchange, HttpStatus.BAD_REQUEST,
                    "Request body is empty. Provide a JSON array of filters.", config, true);
                return;
            }

            // Parse filter array
            var filters = JsonUtil.fromJson(body, new TypeReference<List<Filter>>() {
            });

            // Validate all filters
            validateFilters(filters);

            // Execute query
            var response = db.queryTelemetry(filters);

            Helper.sendJson(exchange, HttpStatus.OK, JsonUtil.toJson(response), config, true);
        } catch (IllegalArgumentException e) {
            Helper.sendError(exchange, HttpStatus.BAD_REQUEST, e.getMessage(), config, true);
        } catch (SQLException e) {
            Helper.sendError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Database error: " + e.getMessage(), config, true);
        } catch (Exception e) {
            Helper.sendError(exchange, HttpStatus.BAD_REQUEST, "Invalid query: " + e.getMessage(), config, true);
        }
    }

    /**
     * Validate all filters against the schema registry and core field definitions.
     * <p>
     * Validation rules: - Field must exist (either core field or registered metric) - Operation must be compatible with
     * the field's data type: - NUMBER: Equals, LessThan, GreaterThan - BOOLEAN: Equals - STRING: Equals, Contains -
     * Value must be parseable for the field's data type
     */
    private void validateFilters(List<Filter> filters) throws SQLException {
        if (filters == null || filters.isEmpty()) {
            return; // No filters = return all data
        }
        try (var conn = db.getConnection()) {
            for (var filter : filters) {
                if (filter.field() == null || filter.field().isBlank()) {
                    throw new IllegalArgumentException("Filter field cannot be null or empty");
                }

                var field = filter.field();
                var operation = filter.operation();
                var value = filter.value();

                // Check if it's a core field or a registered metric
                if (CoreField.isCoreField(field)) {
                    validateCoreFieldFilter(field, operation, value);
                } else {
                    validateMetricFilter(conn, field, operation, value);
                }
            }
        }
    }

    /**
     * Validate a filter against a core field.
     */
    private void validateCoreFieldFilter(@NonNull String field, @NonNull String operation, @NonNull Object value) {
        var coreField = CoreField.getCoreField(field);
        if (coreField == null) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }

        var dataType = coreField.getDataType();

        // Validate operation compatibility
        var allowedOps = getAllowedOperations(dataType);
        if (!allowedOps.contains(operation)) {
            throw new IllegalArgumentException(
                String.format("Operation '%s' is invalid for field '%s' of type %s. Allowed: %s",
                    operation, field, dataType, allowedOps));
        }

        // For NUMBER fields, validate the value is numeric
        if ("NUMBER".equals(dataType)) {
            if (value instanceof Number) {
                return; // OK
            }
            if (value instanceof String) {
                try {
                    Double.parseDouble((String) value);
                    return; // OK
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                        String.format("Value '%s' is not a valid number for field '%s'", value, field));
                }
            }
            throw new IllegalArgumentException(
                String.format("Value '%s' is not a valid number for field '%s'", value, field));
        }

        // For DATE fields, validate the value is a string
        if ("DATE".equals(dataType)) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                    String.format("Value '%s' is not a valid date for field '%s'. Use ISO-8601 format.", value, field));
            }
        }
    }

    /**
     * Validate a filter against a registered metric definition.
     */
    private void validateMetricFilter(@NonNull Connection conn, @NonNull String field, @NonNull String operation, @NonNull Object value)
        throws SQLException {
        // Look up metric definition
        var def = db.getMetricDefinition(conn, field);
        if (def == null) {
            throw new IllegalArgumentException("Unknown metric field: " + field);
        }

        var dataType = def.dataType();

        // Validate operation compatibility
        var allowedOps = getAllowedOperations(dataType);
        if (!allowedOps.contains(operation)) {
            throw new IllegalArgumentException(
                String.format("Operation '%s' is invalid for metric '%s' of type %s. Allowed: %s",
                    operation, field, dataType, allowedOps));
        }

        switch (dataType) {
            case "NUMBER" -> {
                if (value instanceof Number) {
                    return; // OK
                }
                if (value instanceof String) {
                    try {
                        Double.parseDouble((String) value);
                        return; // OK
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                            String.format("Value '%s' is not a valid number for metric '%s'", value, field));
                    }
                }
                throw new IllegalArgumentException(
                    String.format("Value '%s' is not a valid number for metric '%s'", value, field));
            }
            case "BOOLEAN" -> {
                switch (value) {
                    case Boolean _ -> {
                        return; // OK
                    }
                    case Number _ -> {
                        return; // OK (0 = false, non-zero = true)
                    }
                    case String s -> {
                        var lower = s.toLowerCase();
                        if ("true".equals(lower) || "false".equals(lower) ||
                            "on".equals(lower) || "off".equals(lower) ||
                            "1".equals(lower) || "0".equals(lower)) {
                            return; // OK
                        }
                        throw new IllegalArgumentException(
                            String.format("Value '%s' is not a valid boolean for metric '%s'. " +
                                "Use true/false, on/off, or 1/0.", value, field));
                    }
                    default -> {
                    }
                }
                throw new IllegalArgumentException(
                    String.format("Value '%s' is not a valid boolean for metric '%s'", value, field));
            }
            case "STRING" -> {
                // Any value is acceptable for STRING - it will be converted to string
            }
        }
    }

    /**
     * Get allowed operations for a given data type.
     */
    private List<String> getAllowedOperations(@NonNull String dataType) {
        return switch (dataType) {
            case "NUMBER" -> List.of("Equals", "LessThan", "GreaterThan");
            case "BOOLEAN" -> List.of("Equals");
            case "STRING" -> List.of("Equals", "Contains");
            case "DATE" -> List.of("Equals", "LessThan", "GreaterThan", "Contains");
            default -> throw new IllegalArgumentException("Unknown data type: " + dataType);
        };
    }
}
