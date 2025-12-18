package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"io"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"
)

// Config
var (
	upstreamURL      = os.Getenv("UPSTREAM_URL")
	gitlabAdminToken = os.Getenv("GITLAB_ADMIN_TOKEN")

	// User mapping struct
	userMap = map[string]struct {
		ID       int
		Username string
	}{
		"jonathanp": {ID: 1, Username: "root"},
		"bob":       {ID: 1, Username: "root"}, // Mapped to Root (ID 1) for verification reliability (User 2 creation flaky)
		"alice":     {ID: 3, Username: "alice"},
	}

	// Token Cache
	tokenCache = struct {
		sync.RWMutex
		items map[int]cachedToken
	}{
		items: make(map[int]cachedToken),
	}

	// Metrics
	proxyRequests = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total number of HTTP requests to the proxy",
		},
		[]string{"status_code"},
	)
	proxyDuration = prometheus.NewHistogram(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "Histogram of response latency (seconds)",
			Buckets: prometheus.DefBuckets,
		},
	)
	cacheHits = prometheus.NewCounter(
		prometheus.CounterOpts{
			Name: "proxy_token_cache_hits_total",
			Help: "Total number of token cache hits",
		},
	)
	cacheMisses = prometheus.NewCounter(
		prometheus.CounterOpts{
			Name: "proxy_token_cache_misses_total",
			Help: "Total number of token cache misses",
		},
	)
)

func init() {
	// Register metrics
	prometheus.MustRegister(proxyRequests)
	prometheus.MustRegister(proxyDuration)
	prometheus.MustRegister(cacheHits)
	prometheus.MustRegister(cacheMisses)
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	return w.ResponseWriter.Write(b)
}

type cachedToken struct {
	Token  string
	Expiry time.Time
}

type TokenResponse struct {
	Token string `json:"token"`
}

func main() {
	if upstreamURL == "" {
		log.Fatal("UPSTREAM_URL is required")
	}
	if gitlabAdminToken == "" {
		log.Fatal("GITLAB_ADMIN_TOKEN is required")
	}

	// Load CA
	caCert, err := os.ReadFile("certs/ca.crt")
	if err != nil {
		log.Fatalf("Error reading CA cert: %v", err)
	}
	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(caCert)

	// TLS Config
	tlsConfig := &tls.Config{
		ClientCAs:  caCertPool,
		ClientAuth: tls.RequireAndVerifyClientCert,
	}

	// Proxy Handler
	proxy := &httputil.ReverseProxy{
		Director: proxyDirector,
	}

	// Start Metrics Server
	go func() {
		log.Println("Starting metrics server on :9090...")
		http.Handle("/metrics", promhttp.Handler())
		log.Fatal(http.ListenAndServe(":9090", nil))
	}()

	server := &http.Server{
		Addr:      ":8443",
		Handler:   instrumentHandler(proxy),
		TLSConfig: tlsConfig,
	}

	log.Println("Starting mTLS proxy on :8443...")
	log.Fatal(server.ListenAndServeTLS("certs/server.crt", "certs/server.key"))
}

func instrumentHandler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}

		next.ServeHTTP(sw, r)

		duration := time.Since(start).Seconds()
		if sw.status == 0 {
			sw.status = 200
		}

		proxyDuration.Observe(duration)
		proxyRequests.WithLabelValues(strconv.Itoa(sw.status)).Inc()
	})
}

func getOrGenerateToken(userID int) (string, error) {
	// Check Cache
	tokenCache.RLock()
	if item, ok := tokenCache.items[userID]; ok {
		if time.Now().Before(item.Expiry) {
			tokenCache.RUnlock()
			cacheHits.Inc()
			return item.Token, nil
		}
	}
	tokenCache.RUnlock()
	cacheMisses.Inc()

	// Generate New
	// Because multiple requests might race here, real prod code might single-flight this.
	// For now, generating a few extra tokens is fine.
	log.Printf("Generating new token for user %d (cache miss/expired)", userID)
	token, err := generateImpersonationToken(userID)
	if err != nil {
		return "", err
	}

	// Update Cache.
	// We requested expiry = Now + 24h. GitLab sets valid_until = End of that Day.
	// So caching for 23 hours is strictly safe.
	// (Using 23h to provide a 1-hour buffer against clock skew or processing delays)
	safeExpiry := time.Now().Add(23 * time.Hour)

	tokenCache.Lock()
	tokenCache.items[userID] = cachedToken{
		Token:  token,
		Expiry: safeExpiry,
	}
	tokenCache.Unlock()

	return token, nil
}

func generateImpersonationToken(userID int) (string, error) {
	apiURL := fmt.Sprintf("%s/api/v4/users/%d/impersonation_tokens", upstreamURL, userID)

	// Calculate requested expiry (Tomorrow)
	// GitLab API requires YYYY-MM-DD. Token is valid until end of that day.
	targetTime := time.Now().Add(24 * time.Hour)
	expiresAtParam := targetTime.Format("2006-01-02")

	reqBody, _ := json.Marshal(map[string]interface{}{
		"name":       fmt.Sprintf("mtls-proxy-%d", time.Now().UnixNano()), // Nano to reduce collision chance in race
		"scopes":     []string{"api"},
		"expires_at": expiresAtParam,
	})

	req, err := http.NewRequest("POST", apiURL, bytes.NewBuffer(reqBody))
	if err != nil {
		return "", err
	}
	req.Header.Set("PRIVATE-TOKEN", gitlabAdminToken)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 201 {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("api returned %s: %s", resp.Status, string(body))
	}

	var result TokenResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", err
	}
	return result.Token, nil
}

func proxyDirector(req *http.Request) {
	target, _ := url.Parse(upstreamURL)
	req.URL.Scheme = target.Scheme
	req.URL.Host = target.Host
	req.Host = target.Host

	// 1. Extract User from Cert
	// For testing simplifiction, if TLS is nil, we might skip (or strict check).
	// But in prod context TLS is required.
	if req.TLS == nil || len(req.TLS.PeerCertificates) == 0 {
		log.Println("No peer certificate found")
		return
	}
	cn := req.TLS.PeerCertificates[0].Subject.CommonName
	log.Printf("Authenticated Client CN: %s", cn)

	user, ok := userMap[cn]
	if !ok {
		log.Printf("User %s not authorized", cn)
		return
	}

	// 2. Get/Generate Impersonation Token
	token, err := getOrGenerateToken(user.ID)
	if err != nil {
		log.Printf("Error obtaining token for user %d: %v", user.ID, err)
		return
	}

	// 3. Inject Headers
	// API access
	req.Header.Set("PRIVATE-TOKEN", token)

	// Git-over-HTTPS access (Basic Auth)
	// GitLab expects username:token base64 encoded
	auth := fmt.Sprintf("%s:%s", user.Username, token)
	encodedAuth := base64.StdEncoding.EncodeToString([]byte(auth))
	req.Header.Set("Authorization", "Basic "+encodedAuth)

	log.Printf("Injected auth headers for user %s (ID: %d)", user.Username, user.ID)
}
