package exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DatabaseConfig and ConnectionFactory configuration validation.
 * Tests configuration parsing, missing environment variable handling,
 * URL validation, password redaction, and schema.sql resource availability.
 */
class DatabaseConfigTest {

    @Test
    @DisplayName("Valid DatabaseConfig creation succeeds with getters and redacted toString")
    void validConfigCreation() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:mysql://localhost:3306/stock_exchange",
                "exchange_user",
                "secret123",
                15,
                3000
        );

        assertEquals("jdbc:mysql://localhost:3306/stock_exchange", config.getUrl());
        assertEquals("exchange_user", config.getUser());
        assertEquals("secret123", config.getPassword());
        assertEquals(15, config.getPoolSize());
        assertEquals(3000, config.getConnectionTimeoutMs());

        // Password must NEVER appear in toString
        String stringRep = config.toString();
        assertFalse(stringRep.contains("secret123"), "toString must redact password");
        assertTrue(stringRep.contains("REDACTED"), "toString must indicate redacted password");
    }

    @Test
    @DisplayName("Rejects null or blank URL with actionable error")
    void rejectsInvalidUrl() {
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("", "user", "pass"));
        assertTrue(ex1.getMessage().contains("DB_URL"));

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("jdbc:postgresql://localhost/db", "user", "pass"));
        assertTrue(ex2.getMessage().contains("jdbc:mysql:"));
    }

    @Test
    @DisplayName("Rejects null or blank user with actionable error")
    void rejectsInvalidUser() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("jdbc:mysql://localhost:3306/db", "   ", "pass"));
        assertTrue(ex.getMessage().contains("DB_USER"));
    }

    @Test
    @DisplayName("Rejects null password with actionable error")
    void rejectsNullPassword() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("jdbc:mysql://localhost:3306/db", "user", null));
        assertTrue(ex.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    @DisplayName("Rejects non-positive pool size and timeout")
    void rejectsInvalidPoolSizeOrTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("jdbc:mysql://localhost:3306/db", "user", "pass", 0, 1000));
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseConfig("jdbc:mysql://localhost:3306/db", "user", "pass", 10, -5));
    }

    @Test
    @DisplayName("DatabaseConfig.fromEnv throws ExchangeException if environment variables are missing")
    void fromEnvMissingVariables() {
        // When running in an environment without DB_URL / DB_USER / DB_PASSWORD set
        if (System.getenv("DB_URL") == null) {
            ExchangeException ex = assertThrows(ExchangeException.class, DatabaseConfig::fromEnv);
            assertTrue(ex.getMessage().contains("Missing required database environment variable(s)"));
            assertTrue(ex.getMessage().contains("DB_URL"));
        }
    }

    @Test
    @DisplayName("schema.sql resource exists on classpath and contains all 5 required tables")
    void schemaSqlResourcePresentAndValid() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/schema.sql")) {
            assertNotNull(is, "schema.sql must be accessible on classpath");
            String content = new String(is.readAllBytes());
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS sequence_generator"), "Must define sequence_generator table");
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS accounts"), "Must define accounts table");
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS holdings"), "Must define holdings table");
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS orders"), "Must define orders table");
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS idempotency_records"), "Must define idempotency_records table");
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS trades"), "Must define trades table");
            assertTrue(content.contains("uk_maker_fill"), "Must have unique maker fill constraint");
            assertFalse(content.contains("event_journal"), "Must not include conflicting event journal");
        }
    }

    @Test
    @DisplayName("ConnectionFactory instantiates with MySQL driver and reports connection failure on dummy host")
    void connectionFactoryHandlesUnreachableDatabase() {
        DatabaseConfig dummy = DatabaseConfig.forTest("jdbc:mysql://127.0.0.1:65534/dummy", "user", "pass");
        ConnectionFactory factory = new ConnectionFactory(dummy);
        assertNotNull(factory.getConfig());
        // Should report false quickly without crashing
        assertFalse(factory.testConnection(), "Unreachable dummy database should return false");
    }
}
