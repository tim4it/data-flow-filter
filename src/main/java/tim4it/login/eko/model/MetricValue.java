package tim4it.login.eko.model;

/**
 * Represents a single metric value from telemetry_metrics.
 * Either numValue or strValue is populated depending on the metric type.
 */
public record MetricValue(String name, Double numValue, String strValue) {

    /**
     * Get the effective value as an Object (numValue if present, otherwise strValue).
     */
    public Object getValue() {
        return numValue != null ? numValue : strValue;
    }
}