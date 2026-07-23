package tim4it.login.eko.http;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP status codes with numeric value and reason phrase. Provides O(1) lookup by code via a static map.
 */
@Getter
@AllArgsConstructor
public enum HttpStatus {

    CONTINUE(100, "Continue"),
    SWITCHING_PROTOCOLS(101, "Switching Protocols"),

    OK(200, "OK"),
    CREATED(201, "Created"),
    ACCEPTED(202, "Accepted"),
    NO_CONTENT(204, "No Content"),

    MULTIPLE_CHOICES(300, "Multiple Choices"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),

    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),

    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),

    UNKNOWN(-1, "Unknown Status");

    final int code;
    final String description;

    /**
     * Lookup by numeric code — O(1) via a pre-built map.
     */
    public static HttpStatus getStatusFromCode(int code) {
        return CODE_MAP.getOrDefault(code, UNKNOWN);
    }

    private static final Map<Integer, HttpStatus> CODE_MAP;

    static {
        var map = new HashMap<Integer, HttpStatus>(values().length);
        for (var status : values()) {
            map.put(status.code, status);
        }
        CODE_MAP = Map.copyOf(map);
    }
}
