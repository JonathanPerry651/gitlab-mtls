package com.gitlab.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

public class ProxyHandler implements HttpHandler {
    private final TokenManager tokenManager;
    private final HttpClient httpClient;
    
    // Hardcoded User Map
    private static final Map<String, User> USER_MAP = Map.of(
        "jonathanp", new User(1, "root"),
        "bob", new User(1, "root"),
        "alice", new User(3, "alice")
    );

    record User(int id, String username) {}

    private static final Counter requests = Counter.build()
            .name("http_requests_total")
            .labelNames("status_code")
            .help("Total requests")
            .register();

    private static final Histogram duration = Histogram.build()
            .name("http_request_duration_seconds")
            .help("Request latency")
            .register();

    public ProxyHandler(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Histogram.Timer timer = duration.startTimer();
        int statusCode = 200;
        try {
            if (!(exchange instanceof HttpsExchange httpsExchange)) {
                sendError(exchange, 400, "TLS Required");
                statusCode = 400;
                return;
            }

            // 1. Authenticate Client
            String cn = extractCN(httpsExchange.getSSLSession());
            if (cn == null || !USER_MAP.containsKey(cn)) {
                System.out.println("Unauthorized CN: " + cn);
                sendError(exchange, 403, "Unauthorized");
                statusCode = 403;
                return;
            }
            User user = USER_MAP.get(cn);
            System.out.println("Authenticated CN: " + cn + " -> User: " + user.username);

            // 2. Get Token
            String token = tokenManager.getOrGenerateToken(user.id);

            // 3. Forward Request
            forwardRequest(exchange, user, token);

        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            statusCode = 500;
        } finally {
            timer.observeDuration();
            requests.labels(String.valueOf(statusCode)).inc();
            exchange.close();
        }
    }

    private void forwardRequest(HttpExchange exchange, User user, String token) throws IOException, InterruptedException {
        String path = exchange.getRequestURI().toString();
        URI targetUri = URI.create(Config.UPSTREAM_URL + path);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofInputStream(() -> exchange.getRequestBody()));

        // Copy Headers
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("Content-Length")) {
                v.forEach(val -> reqBuilder.header(k, val));
            }
        });

        // Inject Auth
        reqBuilder.header("PRIVATE-TOKEN", token);
        String authParams = user.username + ":" + token;
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(authParams.getBytes(StandardCharsets.UTF_8));
        reqBuilder.header("Authorization", basicAuth);

        // Send
        HttpResponse<InputStream> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

        // Response Headers
        response.headers().map().forEach((k, v) -> {
            v.forEach(val -> exchange.getResponseHeaders().add(k, val));
        });

        // Response Body
        exchange.sendResponseHeaders(response.statusCode(), 0); // 0 = Chunked
        try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
            in.transferTo(out);
        }
    }

    private String extractCN(SSLSession session) {
        try {
            Certificate[] certs = session.getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                String dn = x509.getSubjectX500Principal().getName();
                System.out.println("DEBUG: Client DN: " + dn);
                // Rudimentary CN extraction
                for (String part : dn.split(",")) {
                    if (part.trim().startsWith("CN=")) {
                        return part.trim().substring(3);
                    }
                }
            } else {
                System.out.println("DEBUG: No certificates found or not X509");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
