package tim4it.login.eko.model;

import java.util.List;

/**
 * A single telemetry event result with machine info and all associated metrics.
 */
public record TelemetryEventResult(long eventId,
                                   String serialNumber,
                                   String vehicleType,
                                   String recordedAt,
                                   Double latitude,
                                   Double longitude,
                                   Double groundSpeed,
                                   List<MetricValue> metrics) {
}
