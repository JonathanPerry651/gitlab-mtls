package main

import (
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Mock GitLab Server
func mockGitLabServer(t *testing.T) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify Admin Token
		if r.Header.Get("PRIVATE-TOKEN") != gitlabAdminToken {
			t.Errorf("Expected Admin Token, got %s", r.Header.Get("PRIVATE-TOKEN"))
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		// Mock Token Response
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{
			"token": "mock-token-12345",
		})
	}))
}

func TestGetOrGenerateToken(t *testing.T) {
	// Setup Mock Environment
	ts := mockGitLabServer(t)
	defer ts.Close()
	upstreamURL = ts.URL
	gitlabAdminToken = "test-admin-token"

	// Reset Cache
	tokenCache.items = make(map[int]cachedToken)

	// 1. First Call (Cache Miss)
	token, err := getOrGenerateToken(123)
	if err != nil {
		t.Fatalf("First call failed: %v", err)
	}
	if token != "mock-token-12345" {
		t.Errorf("Expected mock-token-12345, got %s", token)
	}

	// Verify Cache was populated
	tokenCache.RLock()
	item, ok := tokenCache.items[123]
	tokenCache.RUnlock()
	if !ok {
		t.Error("Cache not populated")
	}
	if item.Token != "mock-token-12345" {
		t.Error("Cache item token mismatch")
	}

	// 2. Second Call (Cache Hit)
	// We can't easily assert "no network call" without a spy, but we can manually
	// modify the cache to prove it was used.
	tokenCache.Lock()
	tokenCache.items[123] = cachedToken{
		Token:  "cached-token-999",
		Expiry: time.Now().Add(1 * time.Hour),
	}
	tokenCache.Unlock()

	token, err = getOrGenerateToken(123)
	if err != nil {
		t.Fatalf("Second call failed: %v", err)
	}
	if token != "cached-token-999" {
		t.Errorf("Expected cached-token-999, got %s", token)
	}
}

func TestProxyDirector(t *testing.T) {
	// Setup Mock Environment
	ts := mockGitLabServer(t)
	defer ts.Close()
	upstreamURL = ts.URL
	gitlabAdminToken = "test-admin-token"

	// Reset Cache
	tokenCache.items = make(map[int]cachedToken)

	// Define Test Cases
	tests := []struct {
		name           string
		clientCN       string
		expectHeader   bool
		expectLog      bool // Hard to test log, skipping
		expectedStatus int  // Not checking status here as Director doesn't return response
	}{
		{
			name:         "Valid User",
			clientCN:     "jonathanp",
			expectHeader: true,
		},
		{
			name:         "Invalid User",
			clientCN:     "unknown-hacker",
			expectHeader: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			req, _ := http.NewRequest("GET", "https://proxy.local/api/v4/user", nil)

			// Mock TLS State
			req.TLS = &tls.ConnectionState{
				PeerCertificates: []*x509.Certificate{
					{
						Subject: pkix.Name{
							CommonName: tc.clientCN,
						},
					},
				},
			}

			proxyDirector(req)

			// Assertion
			hasToken := req.Header.Get("PRIVATE-TOKEN") != ""
			hasBasic := req.Header.Get("Authorization") != ""

			if tc.expectHeader {
				if !hasToken {
					t.Error("Expected PRIVATE-TOKEN header, got empty")
				}
				if !hasBasic {
					t.Error("Expected Authorization header, got empty")
				}
			} else {
				if hasToken {
					t.Error("Expected NO header, got PRIVATE-TOKEN")
				}
			}
		})
	}
}
