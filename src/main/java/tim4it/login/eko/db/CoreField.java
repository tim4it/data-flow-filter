package tim4it.login.eko.db;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

import java.util.Arrays;

/**
 * Core field definitions with their data types for validation. These fields are stored directly in telemetry_events,
 * not in EAV metrics.
 */
@Getter
@AllArgsConstructor
public enum CoreField {
    RECORDED_AT("DATE", "recordedAt", "te.recorded_at"),
    LATITUDE("NUMBER", "latitude", "te.latitude"),
    LONGITUDE("NUMBER", "longitude", "te.longitude"),
    GROUND_SPEED("NUMBER", "groundSpeed", "te.ground_speed"),
    SERIAL_NUMBER("STRING", "serialNumber", "m.serial_number"),
    VEHICLE_TYPE("STRING", "vehicleType", "m.vehicle_type");

    final String dataType;
    final String name;
    final String columnName;

    public static boolean isCoreField(@NonNull String fieldName) {
        return Arrays.stream(values()).map(f -> f.name)
            .anyMatch(n -> n.equalsIgnoreCase(fieldName));
    }

    public static CoreField getCoreField(@NonNull String fieldName) {
        return Arrays.stream(values())
            .filter(f -> f.name.equalsIgnoreCase(fieldName))
            .findFirst()
            .orElse(null);
    }

    public static String coreFieldToColumn(@NonNull String fieldName) {
        return Arrays.stream(values())
            .filter(f -> f.name.equalsIgnoreCase(fieldName))
            .findFirst()
            .map(cf -> cf.columnName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown core field: " + fieldName));
    }
}
