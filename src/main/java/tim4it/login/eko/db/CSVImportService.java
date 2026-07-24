package tim4it.login.eko.db;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import lombok.NonNull;
import tim4it.login.eko.model.MetricDefinition;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service for importing CSV telemetry data into the SQLite database. Handles header normalization, type inference, and
 * EAV storage.
 */
public class CSVImportService {

    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.ENGLISH);

    private static final Pattern UNIT_PATTERN = Pattern.compile("\\s*\\[.*?]\\s*$");

    final SQLiteDB db;

    public CSVImportService(@NonNull SQLiteDB db) {
        this.db = db;
    }

    /**
     * Import a CSV file into the database.
     *
     * @param csvContent The raw CSV content
     * @return Import statistics
     */
    public ImportStats importCSV(@NonNull String csvContent) throws SQLException {
        // Strip UTF-8 BOM if present
        if (csvContent.startsWith("﻿")) {
            csvContent = csvContent.substring(1);
        }

        // Parse CSV
        var settings = new CsvParserSettings();
        settings.getFormat().setDelimiter(';');
        settings.getFormat().setLineSeparator("\n");
        settings.setIgnoreLeadingWhitespaces(true);
        settings.setIgnoreTrailingWhitespaces(true);

        var parser = new CsvParser(settings);
        var rows = parser.parseAll(new StringReader(csvContent));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty");
        }

        var headers = rows.getFirst();
        var dataRows = rows.subList(1, rows.size());

        // Build column index map
        var columnIndex = new HashMap<String, Integer>();
        for (int i = 0; i < headers.length; i++) {
            var originalHeader = headers[i].trim();
            var normalizedName = normalizeHeader(originalHeader);
            columnIndex.put(normalizedName, i);
        }

        // Determine vehicle type from filename or serial number pattern
        var vehicleType = determineVehicleType(dataRows, columnIndex);

        // Register new metric definitions
        Map<String, MetricDefinition> definitions;
        try (var conn = db.getConnection()) {
            definitions = registerMetrics(conn, headers, dataRows);
        }

        // Import data rows
        var imported = 0;
        var errors = 0;
        try (var conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (var row : dataRows) {
                    try {
                        importRow(conn, row, headers, columnIndex, definitions, vehicleType);
                        imported++;
                        // Commit every 500 rows for performance
                        if (imported % 500 == 0) {
                            conn.commit();
                        }
                    } catch (Exception e) {
                        errors++;
                        System.err.println("Error importing row " + (imported + errors) + ": " + e.getMessage());
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        return new ImportStats(imported, errors, headers.length - 1); // -1 for serial number
    }

    /**
     * Normalize a CSV header to a PascalCase metric name. E.g., "GPS longitude [°]" -> "GpsLongitude" "Grain tank
     * unloading [I/O]" -> "GrainTankUnloading"
     */
    static String normalizeHeader(@NonNull String header) {
        // Remove unit annotation in brackets
        var name = UNIT_PATTERN.matcher(header).replaceAll("").trim();

        // Replace special characters with spaces
        name = name.replaceAll("[/°.]", " ");

        // Collapse multiple spaces
        name = name.replaceAll("\\s+", " ");

        // Convert to PascalCase
        var words = name.split("\\s+");
        var result = new StringBuilder();
        for (var word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                result.append(word.substring(1));
            }
        }

        return result.toString();
    }

    /**
     * Determine vehicle type from serial number pattern. A-prefixed serials -> TRACTOR, C-prefixed -> COMBINE
     */
    private String determineVehicleType(@NonNull List<String[]> dataRows, @NonNull Map<String, Integer> columnIndex) {
        if (dataRows.isEmpty()) {
            return "UNKNOWN";
        }

        var serialIdx = columnIndex.get("SerialNumber");
        if (serialIdx == null || serialIdx >= dataRows.getFirst().length) {
            return "UNKNOWN";
        }

        var serial = dataRows.getFirst()[serialIdx].trim().toUpperCase(Locale.ENGLISH);
        if (serial.startsWith("A")) {
            return "TRACTOR";
        } else if (serial.startsWith("C")) {
            return "COMBINE";
        }
        return "UNKNOWN";
    }

    /**
     * Register metric definitions for all CSV columns (except core fields). Infers data type for new metrics from
     * sample data.
     */
    private Map<String, MetricDefinition> registerMetrics(@NonNull Connection conn,
                                                          @NonNull String[] headers,
                                                          @NonNull List<String[]> dataRows) throws SQLException {
        var definitions = new HashMap<String, MetricDefinition>();

        // Skip these columns - they're core fields
        var skipFields = Set.of(
            "DateTime", "SerialNumber", "GPSLongitude", "GPSLatitude",
            "GroundSpeed", "GroundSpeedGearbox");

        for (int i = 0; i < headers.length; i++) {
            var normalizedName = normalizeHeader(headers[i].trim());

            if (skipFields.contains(normalizedName)) {
                continue;
            }

            // Check if already registered
            var existing = db.getMetricDefinition(conn, normalizedName);
            if (existing != null) {
                definitions.put(normalizedName, existing);
                continue;
            }

            // Infer type from header pattern and sample data
            var originalHeader = headers[i].trim();
            var dataType = inferDataType(originalHeader, dataRows, i);

            // Extract unit from header
            var unit = extractUnit(originalHeader);

            var def = new MetricDefinition(normalizedName, dataType, unit, originalHeader);
            db.upsertMetricDefinition(conn, def);
            definitions.put(normalizedName, def);
        }

        return definitions;
    }

    /**
     * Infer the data type of metric from its header and sample values.
     */
    private String inferDataType(@NonNull String header, @NonNull List<String[]> dataRows, int columnIndex) {
        // Check for boolean indicators in header
        if (header.endsWith("[I/O]")) {
            return "BOOLEAN";
        }
        // Default: infer from sample values
        return inferFromSamples(dataRows, columnIndex);
    }

    /**
     * Infer data type from actual sample values.
     */
    private String inferFromSamples(@NonNull List<String[]> dataRows, int columnIndex) {
        int checked = 0;
        for (var row : dataRows) {
            if (checked > 20) {
                break;
            }
            if (columnIndex >= row.length) {
                continue;
            }

            var value = row[columnIndex].trim();
            if (value.isEmpty() || "NA".equalsIgnoreCase(value)) {
                continue;
            }

            // Check for boolean values
            if ("on".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) ||
                "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) ||
                "active".equalsIgnoreCase(value) || "inactive".equalsIgnoreCase(value) ||
                "i".equalsIgnoreCase(value) || "o".equalsIgnoreCase(value)) {
                return "BOOLEAN";
            }

            // Check for numeric values
            try {
                Double.parseDouble(value);
                checked++;
                // If we found 3+ numeric values, assume NUMBER
                if (checked >= 3) {
                    return "NUMBER";
                }
                continue;
            } catch (NumberFormatException e) {
                // Not numeric
            }

            // First non-empty, non-NA value that's not boolean or numeric -> STRING
            return "STRING";
        }

        return "STRING"; // Default
    }

    /**
     * Extract unit from header (text inside brackets).
     */
    private String extractUnit(@NonNull String header) {
        var bracketStart = header.lastIndexOf('[');
        var bracketEnd = header.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            var unit = header.substring(bracketStart + 1, bracketEnd);
            // Skip I/O indicators - they're not units
            if (!"I/O".equalsIgnoreCase(unit) && !unit.isEmpty()) {
                return unit;
            }
        }
        return null;
    }

    /**
     * Import a single CSV row into the database.
     */
    private void importRow(@NonNull Connection conn,
                           @NonNull String[] row,
                           @NonNull String[] headers,
                           @NonNull Map<String, Integer> columnIndex,
                           @NonNull Map<String, MetricDefinition> definitions,
                           @NonNull String vehicleType) throws SQLException {

        // Extract core fields
        var dateTime = parseDateTime(row, columnIndex);
        var serialNumber = parseSerialNumber(row, columnIndex);
        var longitude = parseDouble(row, columnIndex, "GPSLongitude");
        var latitude = parseDouble(row, columnIndex, "GPSLatitude");
        var groundSpeed = parseGroundSpeed(row, columnIndex);

        // Get or create machine
        var machineId = db.getOrCreateMachine(conn, serialNumber, vehicleType);

        // Insert telemetry event
        var eventId = db.insertTelemetryEvent(conn, machineId, dateTime, latitude, longitude, groundSpeed);

        // Insert EAV metrics
        for (int i = 0; i < headers.length; i++) {
            var normalizedName = normalizeHeader(headers[i].trim());

            // Skip core fields
            if (isCoreField(normalizedName)) {
                continue;
            }

            var rawValue = (i < row.length) ? row[i].trim() : "";
            if (rawValue.isEmpty() || "NA".equalsIgnoreCase(rawValue)) {
                continue;
            }

            var def = definitions.get(normalizedName);
            if (def == null) {
                continue;
            }

            Double numValue = null;
            String strValue = null;

            switch (def.dataType()) {
                case "NUMBER" -> {
                    try {
                        numValue = Double.parseDouble(rawValue);
                    } catch (NumberFormatException e) {
                        // Store as string if we can't parse as number
                        strValue = rawValue;
                    }
                }
                case "BOOLEAN" -> numValue = parseBoolean(rawValue) ? 1.0 : 0.0;
                case "STRING" -> strValue = rawValue;
            }

            db.upsertMetric(conn, eventId, normalizedName, numValue, strValue);
        }
    }

    /**
     * Check if a normalized field name is a core field (not stored in EAV).
     */
    private boolean isCoreField(@NonNull String normalizedName) {
        return switch (normalizedName) {
            case "DateTime", "SerialNumber", "GPSLongitude", "GPSLatitude",
                 "GroundSpeed", "GroundSpeedGearbox" -> true;
            default -> false;
        };
    }

    /**
     * Parse the ground speed value from the CSV row. For tractors: use "Ground speed gearbox" if available. For
     * combines: use "Ground speed".
     */
    private Double parseGroundSpeed(@NonNull String[] row, @NonNull Map<String, Integer> columnIndex) {
        // Try "GroundSpeedGearbox" first (tractors)
        var gearboxIdx = columnIndex.get("GroundSpeedGearbox");
        var val1 = parseNumber(row, gearboxIdx);
        if (val1 != null) {
            return val1;
        }

        // Try "GroundSpeed" (combines)
        var speedIdx = columnIndex.get("GroundSpeed");
        return parseNumber(row, speedIdx);
    }

    private Double parseNumber(@NonNull String[] row, Integer gearboxIdx) {
        if (gearboxIdx != null && gearboxIdx < row.length) {
            var val = row[gearboxIdx].trim();
            if (!val.isEmpty() && !"NA".equalsIgnoreCase(val)) {
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Parse the Date/Time field from the CSV row.
     */
    private String parseDateTime(@NonNull String[] row, @NonNull Map<String, Integer> columnIndex) {
        var idx = columnIndex.get("DateTime");
        if (idx == null || idx >= row.length) {
            throw new IllegalArgumentException("Missing Date/Time column");
        }

        var raw = row[idx].trim();
        try {
            var dt = LocalDateTime.parse(raw, DATE_FORMATTER);
            return dt.toString(); // ISO-8601 format
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format: '" + raw +
                "' (expected: 'Mar 31, 2023, 5:54:27 AM')", e);
        }
    }

    /**
     * Parse the serial number from the CSV row.
     */
    private String parseSerialNumber(@NonNull String[] row, @NonNull Map<String, Integer> columnIndex) {
        var idx = columnIndex.get("SerialNumber");
        if (idx == null || idx >= row.length) {
            throw new IllegalArgumentException("Missing Serial number column");
        }
        return row[idx].trim();
    }

    /**
     * Parse a double value from the CSV row.
     */
    private Double parseDouble(@NonNull String[] row,
                               @NonNull Map<String, Integer> columnIndex,
                               @NonNull String fieldName) {
        var idx = columnIndex.get(fieldName);
        if (idx == null || idx >= row.length) {
            return null;
        }

        var raw = row[idx].trim();
        if (raw.isEmpty() || "NA".equalsIgnoreCase(raw)) {
            return null;
        }

        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse a boolean value from various string representations.
     */
    private boolean parseBoolean(@NonNull String value) {
        var lower = value.toLowerCase(Locale.ENGLISH);
        return switch (lower) {
            case "true", "on", "active", "i", "1" -> true;
            case "false", "off", "inactive", "o", "0" -> false;
            default -> Boolean.parseBoolean(value);
        };
    }

    /**
     * Import statistics result.
     */
    public record ImportStats(int importedRows, int errors, int metricCount) {
    }
}
