package com.gitlab.proxy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Java mTLS Proxy (Java 25)...");

        // 1. Metrics
        DefaultExports.initialize();
        new HTTPServer(Config.METRICS_PORT);
        System.out.println("Metrics listening on :" + Config.METRICS_PORT);

        // 2. SSL Context (Load from PEMs)
        SSLContext sslContext = createSSLContext();

        // 3. Server
        HttpsServer server = HttpsServer.create(new InetSocketAddress(Config.PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLContext c = getSSLContext();
                SSLEngine engine = c.createSSLEngine();
                SSLParameters sslParams = c.getDefaultSSLParameters();
                sslParams.setNeedClientAuth(true); // Require mTLS
                sslParams.setCipherSuites(engine.getEnabledCipherSuites());
                sslParams.setProtocols(engine.getEnabledProtocols());
                params.setSSLParameters(sslParams);
            }
        });

        TokenManager tokenManager = new TokenManager();
        server.createContext("/", new ProxyHandler(tokenManager));

        // Use Virtual Threads for high concurrency
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        
        System.out.println("Proxy listening on :" + Config.PORT);
        server.start();
    }

    private static SSLContext createSSLContext() throws Exception {
        // Load CA
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate caCert;
        try (FileInputStream fis = new FileInputStream("certs/ca.crt")) {
            caCert = cf.generateCertificate(fis);
        }

        // Load Server Cert
        Certificate serverCert;
        try (FileInputStream fis = new FileInputStream("certs/server.crt")) {
            serverCert = cf.generateCertificate(fis);
        }

        // Load Server Key (PKCS8 expected)
        // Warning: Java stdlib handles PKCS8 well. If key is PKCS1, this helps.
        // We assume the key is in PEM format. We need to strip headers.
        String keyContent = Files.readString(Path.of("certs/server.key"));
        String privateKeyPEM = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encodedKey = Base64.getDecoder().decode(privateKeyPEM);
        
        KeyFactory kf = KeyFactory.getInstance("RSA");
        var privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(encodedKey));

        // Create KeyStore
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, "password".toCharArray(), new Certificate[]{serverCert, caCert});

        // KeyManager
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        // TrustStore (CA)
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        System.out.println("Loaded TrustStore with " + trustStore.size() + " certs.");

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }
}
