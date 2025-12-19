# Threat Model: GitLab mTLS Proxy

## 1. Introduction
This document analyzes the security risks associated with the GitLab mTLS Proxy system. It identifies key assets, potential threats, and the mitigations implemented to address them. The model focuses on the specific risks introduced by the proxy architecture, particularly the management of high-privilege credentials.

## 2. Asset Identification

| Asset | Description | Criticality |
| :--- | :--- | :--- |
| **GitLab Admin Token** | The Personal Access Token used by the Proxy to generate user tokens. | **CRITICAL** |
| **User Impersonation Tokens** | Short-lived tokens generated representing specific users. | **HIGH** |
| **Proxy Server Private Key** | Usage key for the Proxy's TLS identity. | **HIGH** |
| **CA Root Key** | The signing key for all Client Certificates. | **CRITICAL** |
| **Client Private Keys** | Keys held by users (Bob, Alice) to prove identity. | **HIGH** |

## 3. Threat Analysis (STRIDE)

### 3.1 Spoofing Identity
*   **Threat**: An attacker presents a stolen Client Certificate (e.g., Bob's key) to the proxy.
*   **Impact**: Full access to GitLab as that user.
*   **Mitigation**:
    *   **mTLS Enforcement**: The proxy strictly validates the certificate signature against the trusted CA.
    *   **Short Lived Certs**: (Recommended) Use short expiry times for client certs.
    *   **Revocation**: *Current Limitation* - No CRL/OCSP implemented; requires manual CA rotation or code-level blocklist.

### 3.2 Tampering with Data
*   **Threat**: An attacker modifies the traffic between Proxy and GitLab.
*   **Impact**: Injection of malicious commands or code.
*   **Mitigation**:
    *   **Kubernetes Networking**: Traffic stays within the cluster network (ClusterIP).
    *   **Trusted Network**: Assumption that the internal cluster network is a trusted zone.
    *   **Upstream TLS**: (Recommended) Configuring GitLab with TLS (`https://...`) ensures cryptographic integrity and confidentiality between Proxy and GitLab, preventing MITM attacks even within the cluster.

### 3.3 Repudiation
*   **Threat**: A user performs a malicious action via the proxy, but denies it.
*   **Impact**: Inability to attribute malicious activity.
*   **Mitigation**:
    *   **Proxy Logs**: The proxy logs the authenticated CN (Identity) for every request before forwarding.
    *   **GitLab Logs**: GitLab logs the actions performed by the Impersonation Token, which is linked to the specific User ID.

### 3.4 Information Disclosure
*   **Threat**: The **Admin Token** leaks via logs, environment variables, or process dump.
*   **Impact**: Full cluster compromise (Attacker can generate tokens for *any* user, including Root).
*   **Mitigation**:
    *   **Env Var Source**: Token is injected via Environment Variable (K8s Secret recommended for production).
    *   **Log Sanitization**: The proxy logs must *never* print the raw `GITLAB_ADMIN_TOKEN` or generated user tokens.
    *   **Memory Safety**: Java's managed memory (JVM) reduces buffer leaks, though heap dumps are a risk if the container is breached.
    *   **Transport Encryption**: Using Upstream TLS prevents the Admin Token from leaking on the wire during token generation calls.

### 3.5 Denial of Service (DoS)
*   **Threat**: An attacker spams the proxy with requests, causing it to flood GitLab with Token Generation calls.
*   **Impact**: GitLab API rate limiting or resource exhaustion.
*   **Mitigation**:
    *   **Token Caching**: The proxy caches tokens for 60 minutes. Repeated requests for the same user do *not* hit the GitLab API.
    *   **Connection Limits**: K8s constraints on the proxy pod.

### 3.6 Elevation of Privilege
*   **Threat**: An attacker compromises the Proxy Container.
*   **Impact**: Access to the `GITLAB_ADMIN_TOKEN` environment variable.
*   **Mitigation**:
    *   **Minimal Base Image**: Use distroless or minimal Alpine images to reduce attack surface.
    *   **Least Privilege**: The proxy pod should not run as root (can be improved in current deployment).
    *   **Network Policy**: Restrict Proxy egress *only* to GitLab Service.

## 4. Specific Risk: Admin Token Compromise
The most critical architectural risk is the Proxy holding a "Key to the Kingdom" (The Admin Token).

*   **Scenario**: Attacker gains shell access to `gitlab-proxy` pod.
*   **Action**: `env | grep TOKEN`.
*   **Result**: Attacker steals Admin Token -> Can create API tokens for Root -> Full Control.
*   **Defense in Depth**:
    *   The Admin Token should have the narrowest possible scope (currently requires `api` scope, which is broad).
    *   Regular rotation of the Admin Token.

## 5. Security Checklist for Production
- [ ] Store `GITLAB_ADMIN_TOKEN` in Kubernetes Secrets, not plaintext YAML.
- [ ] Implement CRL (Certificate Revocation List) checking in the Proxy.
- [ ] Run Proxy container as non-root user.
- [ ] Enable Network Policies to isolate Proxy communication.
- [ ] Externalize User Map to a secure database or config map rather than hardcoding.

## 6. Residual Risk: Token Lingering
Due to the GitLab API limitation requiring `YYYY-MM-DD` format for token expiration, all Impersonation Tokens are valid until midnight UTC of the creation day.
*   **Risk**: If a user is banned or their mTLS cert is revoked, a previously generated token *may* still be valid for up to 24 hours if extracted from the Proxy memory or intercepted.
*   **Mitigation (Future)**: Implement an "Anti-Cache" mechanism where the Proxy explicitly deletes the token via API (`DELETE /impersonation_tokens/:id`) upon cache eviction or session termination.
