package exchange.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * HTTP Client connecting the JavaFX desktop frontend to the Exchange REST API.
 * Compatible with local and deployed endpoints (e.g. Render).
 */
public class ExchangeHttpClient {

    private final String baseUrl;
    private final String apiKey;

    public ExchangeHttpClient(String baseUrl, String apiKey) {
        Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.strip() : null;
    }

    public ExchangeHttpClient(String baseUrl) {
        this(baseUrl, null);
    }

    public record HttpResponse(int statusCode, String body) {}

    public HttpResponse get(String path) throws Exception {
        return request("GET", path, null);
    }

    public HttpResponse post(String path, String jsonBody) throws Exception {
        return request("POST", path, jsonBody);
    }

    public HttpResponse delete(String path) throws Exception {
        return request("DELETE", path, null);
    }

    private HttpResponse request(String method, String path, String jsonBody) throws Exception {
        String fullUrl = baseUrl + (path.startsWith("/") ? path : "/" + path);
        URL url = URI.create(fullUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);

        if (apiKey != null) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }

        if (jsonBody != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        return new HttpResponse(code, body);
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
