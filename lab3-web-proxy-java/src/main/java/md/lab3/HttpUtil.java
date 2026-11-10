package md.lab3;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpUtil {
    private HttpUtil() {}
    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
    static Map<String,String> query(String q) {
        Map<String,String> parsed = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return parsed;
        for (String segment : q.split("&")) {
            String[] kv = segment.split("=", 2);
            parsed.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return parsed;
    }
    static void send(HttpExchange exchange, int status, String data, String type) throws IOException {
        send(exchange, status, data.getBytes(StandardCharsets.UTF_8), type);
    }
    static void send(HttpExchange exchange, int status, byte[] data, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, data.length);
        try (var out = exchange.getResponseBody()) { out.write(data); }
    }
    static String error(String type, String reason) {
        return "{\"error\":\"" + jsonEscape(type) + "\",\"detail\":\"" + jsonEscape(reason) + "\"}";
    }
}
