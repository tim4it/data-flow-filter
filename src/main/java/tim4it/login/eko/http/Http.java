package tim4it.login.eko.http;

/**
 * HTTP header names and media types. Status codes are in {@link HttpStatus}. CORS values are configured in
 * {@link tim4it.login.eko.config.Config}.
 */
public final class Http {

    // ── Header names ──────────────────────────────────────────────────────────

    public static final String CONTENT_TYPE = "Content-Type";
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";

    // ── Media types ───────────────────────────────────────────────────────────

    public static final String APPLICATION_JSON = "application/json";
}
