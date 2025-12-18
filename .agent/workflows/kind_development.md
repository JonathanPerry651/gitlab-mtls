---
description: Procedures for developing with Kind and GitLab
---

# Updating Images in Kind (Podman)

When using Podman with Kind, direct image loading or simple tagging can be flaky. The most reliable method is to save the image as a docker-archive and load it.

1.  **Build with Unique Tag:**
    *Critical:* Do NOT use `latest`. Use a unique timestamp or version to bypass Kind/Containerd caching issues.
    ```bash
    TAG="v$(date +%s)"
    podman build -t localhost/my-image:$TAG .
    ```

2.  **Save as Docker Archive:**
    Use `docker-archive` format.
    ```bash
    podman save --format docker-archive -o my-image.tar localhost/my-image:$TAG
    ```

3.  **Load into Kind:**
    ```bash
    kind load image-archive my-image.tar
    ```

4.  **Update Deployment:**
    Update the deployment to use the *exact* new tag.
    ```bash
    kubectl set image deployment/my-deployment my-container=localhost/my-image:$TAG
    ```
    *Note:* Ensure `imagePullPolicy` is set to `IfNotPresent` or `Never`.

# Managing GitLab Users (when UI is unavailable)

## Via Rails Runner (Resource Intensive)
This method requires spinning up the Rails environment, which may fail on resource-constrained clusters (exit code 137/OOM or "Cannot fork").

```bash
kubectl exec -it <gitlab-pod> -- gitlab-rails runner "User.create!(username: 'bob', email: 'bob@example.com', password: 'ComplexPassword123', password_confirmation: 'ComplexPassword123', name: 'Bob').confirm"
```

## Via API (Recommended)
This is lighter and more reliable, but requires an Admin Token.

1.  **Get/Create Admin Token:**
    If you don't have one, you might need to insert it via SQL (see below).

2.  **Create User:**
    ```bash
    curl --request POST --header "PRIVATE-TOKEN: <your-admin-token>" \
    --data "name=Bob&username=bob&email=bob@example.com&password=ComplexPassword123&skip_confirmation=true" \
    http://<gitlab-url>/api/v4/users
    ```

# Emergency Admin Access Recovery

If the DB is wiped or you lose access:

1.  **Reset Root Password:**
    ```bash
    kubectl exec <pod> -- bash -c "yes ComplexPassword123 | gitlab-rake gitlab:password:reset[root]"
    ```
    *Note: Use a complex password to satisfy policy.*

2.  **Insert Admin Token via SQL (Bypass Rails):**
    First, compute the token digest (e.g. locally or via a one-off runner command if possible), then insert via psql.
    ```sql
    INSERT INTO personal_access_tokens (user_id, name, scopes, token_digest, ...) VALUES (1, ...);
    ```
