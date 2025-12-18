#!/bin/bash
set -e

mkdir -p certs
cd certs

# 1. Generate CA
echo "Generating CA..."
openssl genrsa -out ca.key 2048
openssl req -new -x509 -days 365 -key ca.key -subj "/O=Antigravity/CN=Antigravity Root CA" -out ca.crt

# 2. Generate Server Cert
echo "Generating Server Cert..."
openssl genrsa -out server.key 2048
openssl req -new -key server.key -subj "/CN=gitlab-proxy" -out server.csr
cat <<EOF > server.ext
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = gitlab-proxy
DNS.2 = localhost
IP.1 = 127.0.0.1
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 365 -sha256 -extfile server.ext

# 3. Generate Client Cert
echo "Generating Client Cert..."
openssl genrsa -out client.key 2048
openssl req -new -key client.key -subj "/CN=jonathanp" -out client.csr
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt -days 365 -sha256

# 4. Generate Client Cert for Bob
echo "Generating Client Cert for Bob..."
openssl genrsa -out bob.key 2048
openssl req -new -key bob.key -subj "/CN=bob" -out bob.csr
openssl x509 -req -in bob.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out bob.crt -days 365 -sha256

# 5. Generate Client Cert for Alice
echo "Generating Client Cert for Alice..."
openssl genrsa -out alice.key 2048
openssl req -new -key alice.key -subj "/CN=alice" -out alice.csr
openssl x509 -req -in alice.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out alice.crt -days 365 -sha256

echo "Certificates generated in certs/"
ls -l
