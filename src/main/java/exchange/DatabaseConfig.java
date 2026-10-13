package exchange;

import java.util.Objects;

/**
 * Immutable database configuration value object.
 *
 * Credentials and endpoints are externalized using:
 * <ul>
 *   <li>{@code DB_URL} - JDBC Connection URL (e.g., {@code jdbc:mysql://localhost:3306/stock_exchange})</li>
 *   <li>{@code DB_USER} - Database username</li>
 *   <li>{@code DB_PASSWORD} - Database password</li>
 * </ul>
 *
 * <p>Never defaults or falls back silently to production databases. Missing configuration
 * produces explicit, actionable exceptions indicating which environment variables are missing.</p>
 */
public final class DatabaseConfig {

    private final String url;
    private final String user;
    private final String password;
    private final int poolSize;
    private final int connectionTimeoutMs;

    public DatabaseConfig(String url, String user, String password) {
        // 30s timeout: remote DBs (e.g. Aiven in another region) need more than 5s
        // for TCP + TLS + MySQL handshake, especially on low-CPU free-tier hosts.
        this(url, user, password, 10, 30000);
    }

    public DatabaseConfig(String url, String user, String password, int poolSize, int connectionTimeoutMs) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "Database URL is required. Please set the 'DB_URL' environment variable or provide a valid JDBC URL.");
        }
        if (!url.startsWith("jdbc:mysql:")) {
            // Do not echo the URL: it may contain credentials (e.g. a raw mysql://user:pass@host URI).
            throw new IllegalArgumentException(
                    "Database URL must start with 'jdbc:mysql:'. Use the JDBC form and set DB_USER/DB_PASSWORD separately.");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException(
                    "Database user is required. Please set the 'DB_USER' environment variable.");
        }
        if (password == null) {
            throw new IllegalArgumentException(
                    "Database password is required. Please set the 'DB_PASSWORD' environment variable (can be empty string for local test DB, but not null).");
        }
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive. Provided: " + poolSize);
        }
        if (connectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectionTimeoutMs must be positive. Provided: " + connectionTimeoutMs);
        }

        this.url = url.trim();
        this.user = user.trim();
        this.password = password;
        this.poolSize = poolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * Factory method creating a {@link DatabaseConfig} from environment variables:
     * {@code DB_URL}, {@code DB_USER}, {@code DB_PASSWORD}.
     *
     * @throws ExchangeException with a clear, actionable error message if any variable is missing.
     */
    public static DatabaseConfig fromEnv() {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        StringBuilder missing = new StringBuilder();
        if (url == null || url.isBlank()) missing.append(" DB_URL");
        if (user == null || user.isBlank()) missing.append(" DB_USER");
        if (password == null) missing.append(" DB_PASSWORD");

        if (missing.length() > 0) {
            throw new ExchangeException(
                    "Missing required database environment variable(s):" + missing +
                    ". Example setup in PowerShell:\n" +
                    "  $env:DB_URL = 'jdbc:mysql://localhost:3306/stock_exchange?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'\n" +
                    "  $env:DB_USER = 'exchange_user'\n" +
                    "  $env:DB_PASSWORD = 'your_secure_password'"
            );
        }

        return new DatabaseConfig(url, user, password);
    }

    /**
     * Explicit test-scoped factory helper. Explicitly demarcated so production
     * code cannot invoke it unaware.
     */
    public static DatabaseConfig forTest(String url, String user, String password) {
        return new DatabaseConfig(url, user, password, 2, 2000);
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    /**
     * Password accessor. Never print or log this value.
     */
    public String getPassword() {
        return password;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    @Override
    public String toString() {
        // Redacts password from toString output to prevent log leakage
        return "DatabaseConfig{url='" + url + "', user='" + user + "', poolSize=" + poolSize + ", password=REDACTED}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DatabaseConfig that)) return false;
        return poolSize == that.poolSize &&
                connectionTimeoutMs == that.connectionTimeoutMs &&
                Objects.equals(url, that.url) &&
                Objects.equals(user, that.user) &&
                Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, user, password, poolSize, connectionTimeoutMs);
    }
}