package tim4it.login.eko.model;

import java.util.List;

/**
 * Response object for telemetry query results.
 * Contains a list of matching telemetry events with machine info and metrics.
 */
public record TelemetryQueryResponse(List<TelemetryEventResult> results, int totalCount) {
}
