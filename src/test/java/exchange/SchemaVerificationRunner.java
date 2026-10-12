package exchange;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Live schema verification and constraint validation runner.
 * Connects to MySQL using environment variables, applies schema.sql,
 * checks table presence, and verifies engine constraints using a rolled-back transaction.
 */
public class SchemaVerificationRunner {

    public static void main(String[] args) {
        System.out.println("=== Starting Live MySQL Schema Verification ===");

        DatabaseConfig config;
        try {
            config = DatabaseConfig.fromEnv();
            System.out.println("Loaded config: " + config);
        } catch (Exception e) {
            System.err.println("CONFIGURATION ERROR: " + e.getMessage());
            System.exit(1);
            return;
        }

        ConnectionFactory factory = new ConnectionFactory(config);

        // 1. Connection Ping
        System.out.print("Testing connectivity to MySQL... ");
        if (!factory.testConnection()) {
            System.err.println("FAILED. Could not establish connection to " + config.getUrl());
            System.exit(2);
            return;
        }
        System.out.println("SUCCESS.");

        // 2. Initialize Schema
        System.out.print("Executing schema.sql via ConnectionFactory... ");
        try {
            factory.initializeSchema("/schema.sql");
            System.out.println("SUCCESS.");
        } catch (Exception e) {
            System.err.println("FAILED. Error executing schema: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
            return;
        }

        // 3. Repeat Initialization (Idempotency Test)
        System.out.print("Testing repeat schema.sql execution (idempotency)... ");
        try {
            factory.initializeSchema("/schema.sql");
            System.out.println("SUCCESS (idempotent).");
        } catch (Exception e) {
            System.err.println("FAILED. Repeat execution failed: " + e.getMessage());
            System.exit(4);
            return;
        }

        // 4. Verify Tables Present
        System.out.println("\nVerifying created tables in information_schema:");
        String[] expectedTables = {
            "sequence_generator", "accounts", "holdings", "orders", "idempotency_records", "trades"
        };

        try (Connection conn = factory.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String tableName : expectedTables) {
                try (ResultSet rs = meta.getTables(conn.getCatalog(), null, tableName, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        System.out.println("  [FOUND] Table: " + tableName);
                    } else {
                        System.err.println("  [MISSING] Table: " + tableName);
                        System.exit(5);
                        return;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to query table metadata: " + e.getMessage());
            System.exit(6);
            return;
        }

        // 5. Verify Constraints & Reject Invalid Inserts
        System.out.println("\nTesting database check and unique constraints (inside rolled-back transaction):");
        try (Connection conn = factory.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Seed an account
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO accounts (user_id, total_cash, reserved_cash) VALUES (?, ?, ?)")) {
                    stmt.setLong(1, 99999L);
                    stmt.setBigDecimal(2, new BigDecimal("1000.0000"));
                    stmt.setBigDecimal(3, new BigDecimal("200.0000"));
                    stmt.executeUpdate();
                }

                // Test 5A: Reject total_cash < 0
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO accounts (user_id, total_cash, reserved_cash) VALUES (?, ?, ?)")) {
                    stmt.setLong(1, 99998L);
                    stmt.setBigDecimal(2, new BigDecimal("-50.0000"));
                    stmt.setBigDecimal(3, BigDecimal.ZERO);
                    stmt.executeUpdate();
                    System.err.println("  [FAILED] Check constraint chk_cash_bounds did not reject negative total_cash!");
                    System.exit(7);
                } catch (SQLException e) {
                    System.out.println("  [PASSED] Negative total_cash rejected: " + e.getMessage());
                }

                // Test 5B: Reject reserved_cash > total_cash
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO accounts (user_id, total_cash, reserved_cash) VALUES (?, ?, ?)")) {
                    stmt.setLong(1, 99997L);
                    stmt.setBigDecimal(2, new BigDecimal("100.0000"));
                    stmt.setBigDecimal(3, new BigDecimal("150.0000"));
                    stmt.executeUpdate();
                    System.err.println("  [FAILED] Check constraint chk_cash_bounds did not reject reserved_cash > total_cash!");
                    System.exit(8);
                } catch (SQLException e) {
                    System.out.println("  [PASSED] reserved_cash > total_cash rejected: " + e.getMessage());
                }

                // Test 5C: Reject trade with buy_order_id == sell_order_id
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO trades (id, symbol, buy_order_id, sell_order_id, maker_order_id, maker_fill_seq, buy_user_id, sell_user_id, price, quantity) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, "trade-test-1");
                    stmt.setString(2, "RELIANCE.NS");
                    stmt.setString(3, "same-order-id");
                    stmt.setString(4, "same-order-id");
                    stmt.setString(5, "same-order-id");
                    stmt.setInt(6, 1);
                    stmt.setLong(7, 99999L);
                    stmt.setLong(8, 99999L);
                    stmt.setBigDecimal(9, new BigDecimal("100.0000"));
                    stmt.setLong(10, 10L);
                    stmt.executeUpdate();
                    System.err.println("  [FAILED] Self-trade constraint did not reject buy_order_id == sell_order_id!");
                    System.exit(9);
                } catch (SQLException e) {
                    System.out.println("  [PASSED] buy_order_id == sell_order_id rejected: " + e.getMessage());
                }

            } finally {
                conn.rollback();
                System.out.println("\nTest transaction rolled back successfully. Database remains clean.");
            }
        } catch (SQLException e) {
            System.err.println("Constraint testing transaction error: " + e.getMessage());
            System.exit(10);
            return;
        }

        System.out.println("\n=== ALL LIVE MYSQL VERIFICATIONS PASSED SUCCESSFULLY ===");
    }
}
