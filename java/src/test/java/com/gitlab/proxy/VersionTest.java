package com.gitlab.proxy;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class VersionTest {
    @Test
    public void testJavaVersion() {
        String version = System.getProperty("java.version");
        System.out.println("Running on Java version: " + version);
        assertTrue("Java version should start with 25", version.startsWith("25"));
    }
}
