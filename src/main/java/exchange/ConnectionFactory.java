package exchange;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Factory for obtaining, testing, and initializing JDBC database connections.
 *
 * <p>Connections come from a HikariCP pool that is created lazily on first use, so
 * constructing a factory never touches the network. Closing a connection returned by
 * {@link #getConnection()} hands it back to the pool instead of closing the socket,
 * which avoids a fresh TCP + TLS + MySQL handshake for every transaction.</p>
 *
 * <p>Supports:
 * <ul>
 *   <li>Pooled connection acquisition via {@link #getConnection()}</li>
 *   <li>Connection verification and ping via {@link #testConnection()}</li>
 *   <li>Schema initialization from classpath SQL script via {@link #initializeSchema(String)}</li>
 * </ul>
 *
 * Uses plain JDBC and prepared statements for all dynamic operations.
 */
public class ConnectionFactory {

    private final DatabaseConfig config;
    private volatile HikariDataSource dataSource;

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
     * Borrows a connection from the pool. Closing it returns it to the pool.
     *
     * @return a live {@link Connection}
     * @throws SQLException if no connection can be obtained within the configured timeout
     */
    public Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    private HikariDataSource pool() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (this) {
                ds = dataSource;
                if (ds == null) {
                    ds = createPool();
                    dataSource = ds;
                }
            }
        }
        return ds;
    }

    private HikariDataSource createPool() {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("exchange-db-pool");
        hc.setJdbcUrl(config.getUrl());
        hc.setUsername(config.getUser());
        hc.setPassword(config.getPassword());
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");

        hc.setMaximumPoolSize(config.getPoolSize());
        hc.setMinimumIdle(Math.min(2, config.getPoolSize()));
        // How long getConnection() waits for a free or new connection
        hc.setConnectionTimeout(Math.max(250, config.getConnectionTimeoutMs()));
        // Recycle before the server or a firewall silently drops long-lived sockets,
        // and keep idle connections warm so requests never pay the handshake cost.
        hc.setMaxLifetime(25 * 60_000L);
        hc.setKeepaliveTime(5 * 60_000L);
        // Do not throw at pool creation if the DB is briefly unreachable; fail in getConnection() instead.
        hc.setInitializationFailTimeout(-1);

        // Driver-level socket connect timeout (ms)
        hc.addDataSourceProperty("connectTimeout", "10000");

        HikariDataSource ds = new HikariDataSource(hc);
        Runtime.getRuntime().addShutdownHook(new Thread(ds::close, "db-pool-shutdown"));
        return ds;
    }

    /** Closes the pool, if it was ever created. Safe to call more than once. */
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /**
     * Tests whether a connection can be established to the database.
     *
     * @return true if connection succeeds and ping returns valid within timeout
     */
    public boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn.isValid(Math.max(1, config.getConnectionTimeoutMs() / 1000));
        } catch (SQLException e) {
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