package tim4it.login.eko.model;

/**
 * Represents a metric definition from the schema registry.
 */
public record MetricDefinition(String metricName, String dataType, String unit, String description) {
}