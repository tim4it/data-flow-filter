package tim4it.login.eko.db;

import lombok.Getter;
import lombok.NonNull;
import tim4it.login.eko.model.Filter;
import tim4it.login.eko.model.MetricDefinition;
import tim4it.login.eko.model.MetricValue;
import tim4it.login.eko.model.TelemetryEventResult;
import tim4it.login.eko.model.TelemetryQueryResponse;
import tim4it.login.eko.util.Pair;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * SQLite database access layer for the telemetry EAV schema. Handles connection management, machine/event/metric CRUD,
 * and query execution.
 */
@Getter
public class SQLiteDB implements AutoCloseable {

    private final String dbUrl;
    private final String dbPath;

    public SQLiteDB(@NonNull String databasePath) {
        this.dbPath = Paths.get(System.getProperty("user.dir"), databasePath).toString();
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        System.setProperty("org.sqlite.tmpdir", System.getProperty("java.io.tmpdir"));
    }

    /**
     * Get a new database connection.
     */
    public Connection getConnection() throws SQLException {
        var conn = DriverManager.getConnection(dbUrl);
        try (var stmt = conn.createStatement()) {
            // Enable Write-Ahead Logging: writers append to a separate -wal file instead of
            // locking the main DB file directly, so readers no longer block writers and vice
            // versa (still only one writer at a time). This is a database-level setting
            // persisted to the file itself, so it only needs to be set once, but re-issuing
            // it on every connection is harmless.
            stmt.execute("PRAGMA journal_mode = WAL");

            // Enable enforcement of FOREIGN KEY constraints. SQLite parses FK definitions
            // from the schema but does NOT enforce them unless this is turned on. Without it,
            // inserts with invalid references and deletes that orphan child rows are silently
            // allowed. Unlike journal_mode, this is per-connection, not persisted to the file,
            // so it must be set on every new connection.
            stmt.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    @Override
    public void close() {
        // No persistent connection pool to close; connections are short-lived.
    }

    // =========================================================================
    // Machine operations
    // =========================================================================

    /**
     * Get or create a machine by serial number and vehicle type. Returns the machine ID.
     */
    public long getOrCreateMachine(@NonNull Connection conn,
                                   @NonNull String serialNumber,
                                   @NonNull String vehicleType) throws SQLException {
        // Try to find existing machine
        try (var ps = conn.prepareStatement("SELECT id FROM machines WHERE serial_number = ?")) {
            ps.setString(1, serialNumber);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }

        // Insert new machine
        try (var ps = conn.prepareStatement(
            "INSERT INTO machines (serial_number, vehicle_type) VALUES (?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, serialNumber);
            ps.setString(2, vehicleType);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to get generated machine ID");
            }
        }
    }

    // =========================================================================
    // Metric definition operations
    // =========================================================================

    /**
     * Get a metric definition by name. Returns null if not found.
     */
    public MetricDefinition getMetricDefinition(Connection conn, String metricName) throws SQLException {
        try (var ps = conn.prepareStatement(
            "SELECT metric_name, data_type, unit, description FROM metric_definitions WHERE metric_name = ?")) {
            ps.setString(1, metricName);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new MetricDefinition(
                        rs.getString("metric_name"),
                        rs.getString("data_type"),
                        rs.getString("unit"),
                        rs.getString("description")
                    );
                }
            }
        }
        return null;
    }

    /**
     * Upsert a metric definition.
     */
    public void upsertMetricDefinition(Connection conn, MetricDefinition def) throws SQLException {
        var sql = """
            INSERT INTO metric_definitions (metric_name, data_type, unit, description)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(metric_name) DO UPDATE SET
                data_type = excluded.data_type,
                unit = excluded.unit,
                description = excluded.description
            """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, def.metricName());
            ps.setString(2, def.dataType());
            ps.setString(3, def.unit());
            ps.setString(4, def.description());
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // Telemetry event operations
    // =========================================================================

    /**
     * Insert a telemetry event and return its ID.
     */
    public long insertTelemetryEvent(Connection conn, long machineId, String recordedAt,
                                     Double latitude, Double longitude, Double groundSpeed) throws SQLException {
        try (var ps = conn.prepareStatement(
            "INSERT INTO telemetry_events (machine_id, recorded_at, latitude, longitude, ground_speed) " +
                "VALUES (?, ?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, machineId);
            ps.setString(2, recordedAt);
            ps.setObject(3, latitude);
            ps.setObject(4, longitude);
            ps.setObject(5, groundSpeed);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to get generated event ID");
            }
        }
    }

    /**
     * Insert or update a telemetry metric for an event.
     */
    public void upsertMetric(Connection conn, long eventId, String metricName,
                             Double numValue, String strValue) throws SQLException {
        var sql = """
            INSERT INTO telemetry_metrics (event_id, metric_name, num_value, str_value)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(event_id, metric_name) DO UPDATE SET
                num_value = excluded.num_value,
                str_value = excluded.str_value
            """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            ps.setString(2, metricName);
            ps.setObject(3, numValue);
            ps.setObject(4, strValue);
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // Query operations
    // =========================================================================

    /**
     * Execute a filtered query against the telemetry data. Uses a two-phase approach: 1. Find matching event IDs using
     * core fields + EAV metric subqueries 2. Fetch full event data + all metrics for matching events
     *
     * @param filters List of filter conditions (can be null for no filters)
     * @return TelemetryQueryResponse with matching events and their metrics
     */
    public TelemetryQueryResponse queryTelemetry(List<Filter> filters) throws SQLException {
        try (var conn = getConnection()) {
            // Phase 1: Find matching event IDs
            var eventIds = findMatchingEventIds(conn, filters);

            // Phase 2: Build response with full event data + metrics
            var results = eventIds.isEmpty() ? List.<TelemetryEventResult>of() : buildEventResults(conn, eventIds);
            return new TelemetryQueryResponse(results, results.size());
        }
    }

    /**
     * Find event IDs matching the given filters. If no filters, returns all event IDs.
     */
    private List<Long> findMatchingEventIds(Connection conn, List<Filter> filters) throws SQLException {
        if (filters == null || filters.isEmpty()) {
            // Return all event IDs
            try (var ps = conn.prepareStatement("SELECT id FROM telemetry_events ORDER BY recorded_at");
                 var rs = ps.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
                return ids;
            }
        }

        // Separate core field filters from EAV metric filters
        var coreFilters = new ArrayList<Filter>();
        var eavFilters = new ArrayList<Filter>();
        var metricDefs = new HashMap<String, MetricDefinition>();

        for (var f : filters) {
            if (CoreField.isCoreField(f.field())) {
                coreFilters.add(f);
            } else {
                eavFilters.add(f);
                metricDefs.computeIfAbsent(f.field(), name -> {
                    try {
                        return getMetricDefinition(conn, name);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        // Build the event ID query
        var sql = new StringBuilder();
        sql.append("""
            SELECT DISTINCT te.id
            FROM telemetry_events te
            JOIN machines m ON te.machine_id = m.id
            """);
        var params = new ArrayList<>();

        sql.append(" WHERE ");
        var first = true;

        // Core field conditions
        for (Filter f : coreFilters) {
            if (!first) {
                sql.append(" AND ");
            }
            first = false;
            var col = CoreField.coreFieldToColumn(f.field());
            sql.append(col).append(" ").append(getOperatorSql(f.operation())).append(" ?");
            params.add(prepareFilterValue(f));
        }

        // EAV metric conditions (subquery per unique metric)
        for (var entry : metricDefs.entrySet()) {
            if (!first) {
                sql.append(" AND ");
            }
            first = false;
            var metricName = entry.getKey();
            var storageCol = getStorageColumn(entry.getValue());

            // Find all filters for this metric
            List<Filter> metricFilters = eavFilters.stream()
                .filter(f -> f.field().equals(metricName))
                .toList();

            if (metricFilters.size() == 1) {
                var f = metricFilters.getFirst();
                sql.append("(te.id IN (SELECT event_id FROM telemetry_metrics " +
                        "WHERE metric_name = ? AND ").append(storageCol).append(" ")
                    .append(getOperatorSql(f.operation())).append(" ?))");
                params.add(metricName);
                params.add(prepareFilterValue(f));
            } else {
                // Multiple filters on the same metric
                sql.append("(te.id IN (SELECT event_id FROM telemetry_metrics " +
                    "WHERE metric_name = ? AND (");
                params.add(metricName);
                var firstCond = true;
                for (var f : metricFilters) {
                    if (!firstCond) {
                        sql.append(" AND ");
                    }
                    firstCond = false;
                    sql.append(storageCol).append(" ").append(getOperatorSql(f.operation())).append(" ?");
                    params.add(prepareFilterValue(f));
                }
                sql.append("))");
            }
        }

        try (var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (var rs = ps.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
                return ids;
            }
        }
    }

    /**
     * Build full event results for the given event IDs.
     */
    private List<TelemetryEventResult> buildEventResults(Connection conn, List<Long> eventIds)
        throws SQLException {
        // Build IN clause
        var sql = new StringBuilder();
        sql.append("""
            SELECT te.id AS event_id, m.serial_number, m.vehicle_type,
                   te.recorded_at, te.latitude, te.longitude, te.ground_speed
            FROM telemetry_events te
            JOIN machines m ON te.machine_id = m.id
            WHERE te.id IN (""");
        for (int i = 0; i < eventIds.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
        }
        sql.append(") ORDER BY te.recorded_at");

        var results = new ArrayList<TelemetryEventResult>();
        try (var ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < eventIds.size(); i++) {
                ps.setLong(i + 1, eventIds.get(i));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var lat = rs.getDouble("latitude");
                    var lon = rs.getDouble("longitude");
                    var gs = rs.getDouble("ground_speed");
                    var eventId = rs.getLong("event_id");
                    var event = new TelemetryEventResult(
                        eventId,
                        rs.getString("serial_number"),
                        rs.getString("vehicle_type"),
                        rs.getString("recorded_at"),
                        rs.wasNull() ? null : lat,
                        rs.wasNull() ? null : lon,
                        rs.wasNull() ? null : gs,
                        getMetricsForEvent(conn, eventId)
                    );
                    results.add(event);
                }
            }
        }
        return results;
    }

    /**
     * Get the SQL operator string for a filter operation.
     */
    private String getOperatorSql(String operation) {
        return switch (operation != null ? operation : "Equals") {
            case "Equals" -> "=";
            case "LessThan" -> "<";
            case "GreaterThan" -> ">";
            case "Contains" -> "LIKE";
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    /**
     * Prepare the filter value for SQL binding. - Boolean -> 1.0/0.0 for storage comparison - Contains -> add wildcards
     * for LIKE
     */
    private Object prepareFilterValue(Filter filter) {
        var value = filter.value();
        var op = filter.operation();

        if ("Contains".equals(op) && value instanceof String) {
            return "%" + value + "%";
        }

        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }

        // Convert boolean string variants ("on", "off", "true", "false", "1", "0")
        if (value instanceof String s) {
            var lower = s.toLowerCase();
            if ("true".equals(lower) || "on".equals(lower) || "1".equals(lower)) return 1.0;
            if ("false".equals(lower) || "off".equals(lower) || "0".equals(lower)) return 0.0;
        }

        return value;
    }

    /**
     * Determine which storage column (num_value or str_value) to use for a metric.
     */
    private String getStorageColumn(MetricDefinition def) {
        return switch (def.dataType()) {
            case "NUMBER", "BOOLEAN" -> "num_value";
            case "STRING" -> "str_value";
            default -> throw new IllegalArgumentException("Unknown data type: " + def.dataType());
        };
    }

    /**
     * Get all metrics for a given telemetry event.
     */
    private List<MetricValue> getMetricsForEvent(Connection conn, long eventId) throws SQLException {
        var metrics = new ArrayList<MetricValue>();
        try (var ps = conn.prepareStatement(
            "SELECT metric_name, num_value, str_value FROM telemetry_metrics WHERE event_id = ? " +
                "ORDER BY metric_name")) {
            ps.setLong(1, eventId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var numVal = rs.getDouble("num_value");
                    var mv = new MetricValue(
                        rs.getString("metric_name"),
                        rs.wasNull() ? null : numVal,
                        rs.getString("str_value")
                    );
                    metrics.add(mv);
                }
            }
        }
        return metrics;
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    /**
     * Get the total count of telemetry events and total count of machines.
     *
     * @return pair of telemetry count and machines count
     */
    public Pair<Long, Long> getEventMachineCount() throws SQLException {
        try (var conn = getConnection();
             var stmt1 = conn.createStatement();
             var stmt2 = conn.createStatement();
             var rsTelemetry = stmt1.executeQuery("SELECT COUNT(*) FROM telemetry_events");
             var rsMachines = stmt2.executeQuery("SELECT COUNT(*) FROM machines")) {
            long telemetryCount = rsTelemetry.next() ? rsTelemetry.getLong(1) : 0L;
            long machinesCount = rsMachines.next() ? rsMachines.getLong(1) : 0L;
            return Pair.of(telemetryCount, machinesCount);
        }
    }
}
