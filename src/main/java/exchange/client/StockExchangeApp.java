package exchange.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX Stock Exchange Trading Desktop Application.
 * Connects to either local or deployed Exchange HTTP API (e.g. Render).
 */
public class StockExchangeApp extends Application {

    private ExchangeHttpClient client;
    private ScheduledExecutorService poller;

    // UI Components
    private TextField urlField;
    private TextField apiKeyField;
    private Label connectionStatusLabel;

    private ComboBox<String> symbolBox;
    private TextArea orderBookArea;
    private TextArea tradeTapeArea;
    private TextArea accountArea;
    private TextArea logsArea;

    private TextField orderQtyField;
    private TextField orderPriceField;
    private ComboBox<String> orderSideBox;
    private ComboBox<String> orderTypeBox;
    private TextField userIdField;

    private Label simStatusLabel;
    private Label metricsSummaryLabel;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Stock Exchange Simulator - Terminal");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #1e1e24;");

        // Top: Connection Bar
        HBox topBar = createConnectionBar();
        root.setTop(topBar);

        // Center: Split layout (Left: Order Entry & Account, Center: Order Book, Right: Simulator & Logs)
        GridPane centerGrid = new GridPane();
        centerGrid.setHgap(15);
        centerGrid.setVgap(15);
        centerGrid.setPadding(new Insets(10, 0, 10, 0));

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(30);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(35);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setPercentWidth(35);
        centerGrid.getColumnConstraints().addAll(col1, col2, col3);

        VBox leftPane = createOrderEntryPane();
        VBox middlePane = createOrderBookPane();
        VBox rightPane = createLogsAndSimulatorPane();

        centerGrid.add(leftPane, 0, 0);
        centerGrid.add(middlePane, 1, 0);
        centerGrid.add(rightPane, 2, 0);

        root.setCenter(centerGrid);

        Scene scene = new Scene(root, 1100, 700);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Default local client
        connect();
    }

    private HBox createConnectionBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        bar.setStyle("-fx-background-color: #2b2b36; -fx-background-radius: 6;");

        Label urlLbl = new Label("API URL:");
        urlLbl.setTextFill(Color.LIGHTGRAY);
        urlField = new TextField("http://localhost:8080");
        urlField.setPrefWidth(240);

        Label keyLbl = new Label("API Key:");
        keyLbl.setTextFill(Color.LIGHTGRAY);
        apiKeyField = new TextField();
        apiKeyField.setPromptText("Optional");
        apiKeyField.setPrefWidth(120);

        Button connectBtn = new Button("Connect");
        connectBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        connectBtn.setOnAction(e -> connect());

        connectionStatusLabel = new Label("Disconnected");
        connectionStatusLabel.setTextFill(Color.RED);
        connectionStatusLabel.setFont(Font.font("System", FontWeight.BOLD, 12));

        bar.getChildren().addAll(urlLbl, urlField, keyLbl, apiKeyField, connectBtn, connectionStatusLabel);
        return bar;
    }

    private VBox createOrderEntryPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #2b2b36; -fx-background-radius: 6;");

        Label title = new Label("Place Order");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setTextFill(Color.WHITE);

        userIdField = new TextField("1001");
        orderQtyField = new TextField("10");
        orderPriceField = new TextField("2500.00");

        orderSideBox = new ComboBox<>();
        orderSideBox.getItems().addAll("BUY", "SELL");
        orderSideBox.setValue("BUY");

        orderTypeBox = new ComboBox<>();
        orderTypeBox.getItems().addAll("LIMIT", "MARKET");
        orderTypeBox.setValue("LIMIT");

        Button submitBtn = new Button("Submit Order");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8;");
        submitBtn.setOnAction(e -> submitOrder());

        Label accTitle = new Label("Portfolio & Balances");
        accTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        accTitle.setTextFill(Color.WHITE);

        accountArea = new TextArea();
        accountArea.setEditable(false);
        accountArea.setPrefRowCount(8);
        accountArea.setStyle("-fx-control-inner-background: #1e1e24; -fx-text-fill: #80cbc4; -fx-font-family: monospace;");

        box.getChildren().addAll(
                title,
                new Label("User ID:") {{ setTextFill(Color.LIGHTGRAY); }}, userIdField,
                new Label("Side:") {{ setTextFill(Color.LIGHTGRAY); }}, orderSideBox,
                new Label("Type:") {{ setTextFill(Color.LIGHTGRAY); }}, orderTypeBox,
                new Label("Price:") {{ setTextFill(Color.LIGHTGRAY); }}, orderPriceField,
                new Label("Quantity:") {{ setTextFill(Color.LIGHTGRAY); }}, orderQtyField,
                submitBtn,
                new Separator(),
                accTitle,
                accountArea
        );
        return box;
    }

    private VBox createOrderBookPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #2b2b36; -fx-background-radius: 6;");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Market Depth");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setTextFill(Color.WHITE);

        symbolBox = new ComboBox<>();
        symbolBox.getItems().addAll("RELIANCE.NS", "TCS.NS", "AAPL");
        symbolBox.setValue("RELIANCE.NS");
        symbolBox.setOnAction(e -> refreshMarketData());

        header.getChildren().addAll(title, symbolBox);

        orderBookArea = new TextArea();
        orderBookArea.setEditable(false);
        orderBookArea.setPrefRowCount(14);
        orderBookArea.setStyle("-fx-control-inner-background: #1e1e24; -fx-text-fill: #a5d6a7; -fx-font-family: monospace; -fx-font-size: 13px;");

        Label tapeTitle = new Label("Committed Trade Tape");
        tapeTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        tapeTitle.setTextFill(Color.WHITE);

        tradeTapeArea = new TextArea();
        tradeTapeArea.setEditable(false);
        tradeTapeArea.setPrefRowCount(12);
        tradeTapeArea.setStyle("-fx-control-inner-background: #1e1e24; -fx-text-fill: #ffd54f; -fx-font-family: monospace; -fx-font-size: 12px;");

        box.getChildren().addAll(header, orderBookArea, new Separator(), tapeTitle, tradeTapeArea);
        return box;
    }

    private VBox createLogsAndSimulatorPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #2b2b36; -fx-background-radius: 6;");

        Label simTitle = new Label("Simulation Control");
        simTitle.setFont(Font.font("System", FontWeight.BOLD, 16));
        simTitle.setTextFill(Color.WHITE);

        HBox simControls = new HBox(8);
        Button startSimBtn = new Button("Start Sim");
        startSimBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        startSimBtn.setOnAction(e -> triggerSimAction("start"));

        Button stopSimBtn = new Button("Stop Sim");
        stopSimBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white;");
        stopSimBtn.setOnAction(e -> triggerSimAction("stop"));

        simStatusLabel = new Label("Sim: Stopped");
        simStatusLabel.setTextFill(Color.LIGHTGRAY);
        simControls.getChildren().addAll(startSimBtn, stopSimBtn, simStatusLabel);

        metricsSummaryLabel = new Label("Throughput: 0 op/s | p50: 0µs | p95: 0µs | p99: 0µs");
        metricsSummaryLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
        metricsSummaryLabel.setTextFill(Color.LIGHTCYAN);

        Label logTitle = new Label("Activity Log");
        logTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        logTitle.setTextFill(Color.WHITE);

        logsArea = new TextArea();
        logsArea.setEditable(false);
        logsArea.setPrefRowCount(16);
        logsArea.setStyle("-fx-control-inner-background: #1e1e24; -fx-text-fill: #ffcc80; -fx-font-family: monospace;");

        box.getChildren().addAll(simTitle, simControls, metricsSummaryLabel, new Separator(), logTitle, logsArea);
        return box;
    }

    private void connect() {
        String url = urlField.getText().trim();
        String key = apiKeyField.getText().trim();
        client = new ExchangeHttpClient(url, key);

        new Thread(() -> {
            try {
                var res = client.get("/api/v1/health");
                Platform.runLater(() -> {
                    if (res.statusCode() == 200) {
                        connectionStatusLabel.setText("Connected");
                        connectionStatusLabel.setTextFill(Color.LIGHTGREEN);
                        log("Connected to " + url);
                        startPolling();
                    } else {
                        connectionStatusLabel.setText("Failed (" + res.statusCode() + ")");
                        connectionStatusLabel.setTextFill(Color.RED);
                        log("Connection failed with code " + res.statusCode());
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("Offline");
                    connectionStatusLabel.setTextFill(Color.RED);
                    log("Error connecting to " + url + ": " + ex.getMessage());
                });
            }
        }).start();
    }

    private void startPolling() {
        if (poller != null && !poller.isShutdown()) {
            poller.shutdownNow();
        }
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleAtFixedRate(this::refreshMarketData, 0, 1000, TimeUnit.MILLISECONDS);
    }

    private void refreshMarketData() {
        if (client == null) return;
        String symbol = symbolBox.getValue();
        String userId = userIdField.getText().trim();

        // 1. Fetch Order Book
        try {
            var bookRes = client.get("/api/v1/orderbook/" + symbol);
            if (bookRes.statusCode() == 200) {
                Platform.runLater(() -> orderBookArea.setText(formatOrderBook(bookRes.body())));
            }
        } catch (Exception ignored) {}

        // 2. Fetch User Account
        if (!userId.isBlank()) {
            try {
                var accRes = client.get("/api/v1/accounts/" + userId);
                if (accRes.statusCode() == 200) {
                    Platform.runLater(() -> accountArea.setText(formatAccount(accRes.body())));
                }
            } catch (Exception ignored) {}
        }

        // 3. Fetch Committed Trade Tape
        try {
            var tapeRes = client.get("/api/v1/trades?symbol=" + symbol + "&limit=30");
            if (tapeRes.statusCode() == 200) {
                Platform.runLater(() -> tradeTapeArea.setText(formatTradeTape(tapeRes.body())));
            }
        } catch (Exception ignored) {}

        // 4. Check simulator status and throughput / latency metrics
        try {
            var simRes = client.get("/api/v1/simulator/status");
            if (simRes.statusCode() == 200) {
                boolean running = simRes.body().contains("\"running\":true");
                Platform.runLater(() -> {
                    simStatusLabel.setText("Sim: " + (running ? "RUNNING" : "STOPPED"));
                    simStatusLabel.setTextFill(running ? Color.LIGHTGREEN : Color.LIGHTGRAY);
                });
            }

            var metricsRes = client.get("/api/v1/simulator/metrics");
            if (metricsRes.statusCode() == 200) {
                String mJson = metricsRes.body();
                Platform.runLater(() -> metricsSummaryLabel.setText(formatMetricsSummary(mJson)));
            }
        } catch (Exception ignored) {}
    }

    private void submitOrder() {
        if (client == null) return;
        String symbol = symbolBox.getValue();
        String userIdStr = userIdField.getText().trim();
        String price = orderPriceField.getText().trim();
        String qty = orderQtyField.getText().trim();
        String side = orderSideBox.getValue();
        String type = orderTypeBox.getValue();

        String body = String.format(
                "{\"clientOrderId\":\"cli-%s\",\"userId\":%s,\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"%s\",\"price\":\"%s\",\"quantity\":%s}",
                UUID.randomUUID().toString().substring(0, 8),
                userIdStr,
                symbol,
                side,
                type,
                price,
                qty
        );

        new Thread(() -> {
            try {
                var res = client.post("/api/v1/orders", body);
                Platform.runLater(() -> {
                    if (res.statusCode() == 200) {
                        log("Order submitted successfully: " + side + " " + qty + " " + symbol + " @ " + price);
                    } else {
                        log("Order failed [" + res.statusCode() + "]: " + res.body());
                    }
                    refreshMarketData();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("Submission error: " + ex.getMessage()));
            }
        }).start();
    }

    private void triggerSimAction(String action) {
        if (client == null) return;
        new Thread(() -> {
            try {
                var res = client.post("/api/v1/simulator/" + action, "");
                Platform.runLater(() -> log("Simulator " + action + ": " + res.body()));
            } catch (Exception ex) {
                Platform.runLater(() -> log("Simulator error: " + ex.getMessage()));
            }
        }).start();
    }

    private void log(String msg) {
        logsArea.appendText("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg + "\n");
    }

    private String formatOrderBook(String json) {
        // Pretty print minimal depth view
        return "=== ORDER BOOK ===\n" + json.replace("{", "{\n  ").replace("}", "\n}").replace(",", ",\n  ");
    }

    private String formatAccount(String json) {
        return "=== ACCOUNT ===\n" + json.replace("{", "{\n  ").replace("}", "\n}").replace(",", ",\n  ");
    }

    private String formatTradeTape(String json) {
        return "=== COMMITTED TRADE TAPE ===\n" + json.replace("{", "{\n  ").replace("}", "\n}").replace(",", ",\n  ");
    }

    private String formatMetricsSummary(String json) {
        try {
            String ops = extractValue(json, "ordersPerSecond");
            String p50 = extractValue(json, "latencyP50Micros");
            String p95 = extractValue(json, "latencyP95Micros");
            String p99 = extractValue(json, "latencyP99Micros");
            return "Throughput: " + (ops != null ? ops : "0") + " op/s | p50: " + (p50 != null ? p50 : "0") + "µs | p95: " + (p95 != null ? p95 : "0") + "µs | p99: " + (p99 != null ? p99 : "0") + "µs";
        } catch (Exception e) {
            return "Throughput: -- | Latencies: --";
        }
    }

    private String extractValue(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int start = idx + key.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        if (end == -1) return null;
        return json.substring(start, end).trim();
    }

    @Override
    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
