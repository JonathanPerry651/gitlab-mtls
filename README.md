# GitLab mTLS Proxy

This project implements a Secure mTLS (Mutual TLS) Proxy for GitLab. It is designed to sit in front of a standard GitLab instance and provide strong client certificate authentication while transparently handling upstream authentication using Impersonation Tokens.

## Features

*   **mTLS Termination**: Enforces client certificates for all connections on port 8443.
*   **Identity Mapping**: Maps client X.509 Common Names (CN) to GitLab User IDs.
*   **Transparent Impersonation**: Automatically exchanges Admin Credential for short-lived User Impersonation Tokens.
*   **Git-over-HTTPS Support**: Injects Basic Authentication headers for Git clients, enabling transparent `git clone` with client certs.
*   **Token Caching**: Optimizes performance by caching tokens in memory.

## Architecture

See [Design Document](docs/design.md) for full architecture and sequence diagrams.

## Security

See [Threat Model](docs/threat_model.md) for security analysis and risk mitigations.

## Quick Start (Kind/Kubernetes)

1.  **Generate Certificates**:
    ```bash
    ./scripts/gen-certs.sh
    ```

2.  **Build & Test (Bazel)**:
    ```bash
    # Run unit tests
    bazel test //...

    # Build the application
    bazel build //java/src/main/java/com/gitlab/proxy:Main
    
    # (Optional) Build container image
    # bazel build //:proxy_image
    ```

3.  **Deploy**:
    ```bash
    kubectl apply -f gitlab-basic.yaml
    ```

## Usage

The proxy listens on port **8443**. To access it, you must provide the Client Certificate generated in step 1.

### 1. API Access (curl)

Use `curl` with the client certificate to access GitLab APIs. The proxy will inject the required tokens.

```bash
# Example: Get the authenticated user's profile
curl -v \
  --cert certs/client.crt \
  --key certs/client.key \
  --cacert certs/ca.crt \
  https://localhost:8443/api/v4/user
```

### 2. Git Access (Git-over-HTTPS)

You can clone repositories using Git. You must configure Git to use your client certificate.

**Option A: Global Configuration (Per-User)**
```bash
git config --global http.sslCert $(pwd)/certs/client.crt
git config --global http.sslKey $(pwd)/certs/client.key
git config --global http.sslCAInfo $(pwd)/certs/ca.crt

git clone https://localhost:8443/root/my-project.git
```

**Option B: One-Shot Command**
```bash
git clone \
  -c http.sslCert=$(pwd)/certs/client.crt \
  -c http.sslKey=$(pwd)/certs/client.key \
  -c http.sslCAInfo=$(pwd)/certs/ca.crt \
  https://localhost:8443/root/my-project.git
```

### 3. Monitoring
Metrics are available on port **9090**.
```bash
curl http://localhost:9090/metrics
```

## Structure

*   `java/src/main/java/`: Java 25 source code (SimpleHttpServer).
*   `MODULE.bazel`: Bazel dependency definitions (Bzlmod).
*   `BUILD`: Bazel build rules.
*   `scripts/`: Utility scripts (certificate generation).
*   `docs/`: Detailed documentation.
*   `gitlab-basic.yaml`: Kubernetes manifests for GitLab and Proxy.
