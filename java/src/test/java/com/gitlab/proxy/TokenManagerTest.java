package com.gitlab.proxy;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertEquals;

public class TokenManagerTest {

    // Subclass to mock network calls
    static class MockTokenManager extends TokenManager {
        int generateCount = 0;

        @Override
        protected String generateToken(int userId) throws IOException, InterruptedException {
            generateCount++;
            return "mock-token-" + userId;
        }
    }

    @Test
    public void testTokenCaching() throws Exception {
        MockTokenManager manager = new MockTokenManager();
        int userId = 1;

        // 1. First call: Should generate
        String token1 = manager.getOrGenerateToken(userId);
        assertEquals("mock-token-1", token1);
        assertEquals(1, manager.generateCount);

        // 2. Second call: Should return cached (no generate)
        String token2 = manager.getOrGenerateToken(userId);
        assertEquals("mock-token-1", token2);
        assertEquals(1, manager.generateCount); // Count remains 1

        // 3. Different user: Should generate
        String token3 = manager.getOrGenerateToken(2);
        assertEquals("mock-token-2", token3);
        assertEquals(2, manager.generateCount);
    }
}
