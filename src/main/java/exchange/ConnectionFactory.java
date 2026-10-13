package exchange;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Factory for creating, testing, and initializing JDBC database connections.
 *
 * <p>Supports:
 * <ul>
 *   <li>Direct transactional connection acquisition via {@link #getConnection()}</li>
 *   <li>Connection verification and ping via {@link #testConnection()}</li>
 *   <li>Schema initialization from classpath SQL script via {@link #initializeSchema(String)}</li>
 * </ul>
 *
 * Uses plain JDBC and prepared statements for all dynamic operations.
 */
public class ConnectionFactory {

    private final DatabaseConfig config;

    public ConnectionFactory(DatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "DatabaseConfig cannot be null");
        // Ensure MySQL driver is loaded
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ExchangeException("MySQL Connector/J driver class not found in classpath", e);
        }
    }

    public DatabaseConfig getConfig() {
        return config;
    }

    /**
     * Creates a new JDBC connection using the configured credentials.
     *
     * @return a live {@link Connection}
     * @throws SQLException if connection cannot be established
     */
    public Connection getConnection() throws SQLException {
        DriverManager.setLoginTimeout(config.getConnectionTimeoutMs() / 1000);
        return DriverManager.getConnection(config.getUrl(), config.getUser(), config.getPassword());
    }

    /**
     * Tests whether a connection can be established to the database.
     *
     * @return true if connection succeeds and ping returns valid within timeout
     */
    public boolean testConnection() {
    try (Connection conn = getConnection()) {
        return conn.isValid(config.getConnectionTimeoutMs() / 1000);
    } catch (SQLException e) {
        System.err.println("[DB-DIAGNOSTIC] Connection failed.");
        SQLException current = e;

        while (current != null) {
            System.err.println(
                "[DB-DIAGNOSTIC] Type=" + current.getClass().getSimpleName()
                + ", SQLState=" + current.getSQLState()
                + ", ErrorCode=" + current.getErrorCode()
                + ", Message=" + current.getMessage()
            );
            current = current.getNextException();
        }

        Throwable cause = e.getCause();
        while (cause != null) {
            System.err.println(
                "[DB-DIAGNOSTIC] Cause="
                + cause.getClass().getSimpleName()
                + ": " + cause.getMessage()
            );
            cause = cause.getCause();
        }

        return false;
    }
}

    /**
     * Executes a SQL schema script from the classpath (e.g. {@code /schema.sql}).
     * Splits statements on semicolons and executes each using standard JDBC Statements.
     *
     * @param resourcePath classpath path (e.g. "/schema.sql")
     * @throws ExchangeException if the script resource cannot be found or execution fails
     */
    public void initializeSchema(String resourcePath) {
        String script = loadResource(resourcePath);
        String[] statements = script.split(";");

        try (Connection conn = getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    String trimmed = sql.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new ExchangeException("Failed to initialize database schema from " + resourcePath + ": " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new ExchangeException("Database connection error during schema initialization: " + e.getMessage(), e);
        }
    }

    private String loadResource(String path) {
        InputStream is = getClass().getResourceAsStream(path.startsWith("/") ? path : "/" + path);
        if (is == null) {
            throw new ExchangeException("Schema script resource not found on classpath: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new ExchangeException("Error reading schema resource " + path, e);
        }
    }
}
