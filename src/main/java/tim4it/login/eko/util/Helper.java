package tim4it.login.eko.util;

import com.sun.net.httpserver.HttpExchange;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import tim4it.login.eko.config.Config;
import tim4it.login.eko.http.Http;
import tim4it.login.eko.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@UtilityClass
public class Helper {

    public void sendJson(@NonNull HttpExchange exchange,
                         @NonNull HttpStatus status,
                         @NonNull String body,
                         @NonNull Config config,
                         boolean isAllowOrigin) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(Http.CONTENT_TYPE, Http.APPLICATION_JSON);
        exchange.sendResponseHeaders(status.getCode(), bytes.length == 0 ? -1 : bytes.length);
        if (isAllowOrigin) {
            exchange.getResponseHeaders().set(Http.ACCESS_CONTROL_ALLOW_ORIGIN, config.corsAllowOrigins());
        }
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void sendError(@NonNull HttpExchange exchange,
                          @NonNull HttpStatus status,
                          @NonNull String errorMessage,
                          @NonNull Config config,
                          boolean isAllowOrigin) throws IOException {
        var response = JsonUtil.toJson(Map.of(
            "status", "error",
            "error", errorMessage
        ));
        Helper.sendJson(exchange, status, response, config, isAllowOrigin);
    }
}
