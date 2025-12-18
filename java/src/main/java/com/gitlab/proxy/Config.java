package com.gitlab.proxy;

public class Config {
    public static final String UPSTREAM_URL = System.getenv().getOrDefault("UPSTREAM_URL", "http://localhost");
    public static final String GITLAB_ADMIN_TOKEN = System.getenv("GITLAB_ADMIN_TOKEN");
    public static final int PORT = 8443;
    public static final int METRICS_PORT = 19090;

    static {
        if (GITLAB_ADMIN_TOKEN == null) {
            System.err.println("WARNING: GITLAB_ADMIN_TOKEN is not set!");
        }
    }
}
