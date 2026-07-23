package tim4it.login.eko.model;

/**
 * Represents a single filter condition in a query request.
 * Example: {"field": "GrainTankUnloading", "value": true}
 *          {"field": "GroundSpeed", "operation": "GreaterThan", "value": 4.5}
 */
public record Filter(String field, String operation, Object value) {

    /** Compact constructor — defaults operation to "Equals" when null. */
    public Filter {
        if (operation == null) {
            operation = "Equals";
        }
    }
}