package com.gitlab.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.client.Counter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenManager {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<Integer, CachedToken> cache = new ConcurrentHashMap<>();

    private static final Counter cacheHits = Counter.build()
            .name("proxy_token_cache_hits_total")
            .help("Total number of token cache hits")
            .register();
    
    private static final Counter cacheMisses = Counter.build()
            .name("proxy_token_cache_misses_total")
            .help("Total number of token cache misses")
            .register();

    private record CachedToken(String token, Instant expiry) {}

    public String getOrGenerateToken(int userId) throws IOException, InterruptedException {
        // Check Cache
        CachedToken cached = cache.get(userId);
        if (cached != null && Instant.now().isBefore(cached.expiry)) {
            cacheHits.inc();
            return cached.token;
        }
        cacheMisses.inc();

        // Generate New
        System.out.println("Generating new token for user " + userId);
        String token = generateToken(userId);
        
        // Cache for 23 hours to match GitLab's ~24h window
        cache.put(userId, new CachedToken(token, Instant.now().plus(Duration.ofHours(23))));
        return token;
    }

    protected String generateToken(int userId) throws IOException, InterruptedException {
        String url = String.format("%s/api/v4/users/%d/impersonation_tokens", Config.UPSTREAM_URL, userId);
        
        // Calculate expiry for tomorrow (YYYY-MM-DD)
        String expiresAt = java.time.LocalDate.now().plusDays(1).toString();

        Map<String, Object> body = Map.of(
            "name", "mtls-proxy-" + System.nanoTime(),
            "scopes", new String[]{"api"},
            "expires_at", expiresAt
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", Config.GITLAB_ADMIN_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 201) {
            throw new IOException("GitLab API returned " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.get("token").asText();
    }
}
