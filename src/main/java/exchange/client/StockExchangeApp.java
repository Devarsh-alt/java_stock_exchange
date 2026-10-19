package exchange.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/**
 * Native JavaFX trading terminal for the existing exchange REST API.
 * Keeps the exchange server, matching engine, and MySQL persistence unchanged.
 * Run from the project root with: mvn -Pui javafx:run
 */
public class StockExchangeApp extends Application {
    // Keep these aligned with the active symbols in exchange.Main on the current repository.
    private static final List<String> SYMBOLS = List.of("RELIANCE.NS", "TCS.NS", "INFOSYS.NS");
    private static final String DEFAULT_URL = "https://java-stock-exchange-sg-gjfz.onrender.com";
    private static final int BOOK_DEPTH = 8;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat INTEGER = new DecimalFormat("#,##0");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Preferences preferences = Preferences.userNodeForPackage(StockExchangeApp.class);
    private final List<Double> visiblePrices = new ArrayList<>();
    private volatile String baseUrl;
    private volatile String apiKey = "";
    private volatile String userIdSnapshot = "1001";
    private volatile String selectedSymbol = SYMBOLS.get(0);
    private volatile String selectedSide = "BUY";
    private ScheduledExecutorService poller;

    // Connection/header controls
    private TextField urlField;
    private PasswordField apiKeyField;
    private TextField userIdField;
    private Label serverStatus, databaseStatus, simulatorStatus, noticeLabel;

    // Market / ticket controls
    private ComboBox<String> symbolBox;
    private TextField priceField, quantityField;
    private ChoiceBox<String> orderTypeBox;
    private ToggleButton buyToggle, sellToggle;
    private Button sendOrderButton;
    private Label orderFeedback, orderValueLabel, lastPriceLabel, changeLabel, spreadLabel, chartEmptyLabel;

    // Dashboard metrics
    private Label ordersMetric, tradesMetric, volumeMetric, rejectsMetric;
    private Label cashLabel, reservedLabel, availableLabel;
    private Label p50Label, p95Label, p99Label, ordersPerSecondLabel;
    private ProgressBar p50Bar, p95Bar, p99Bar;

    // Live widgets
    private VBox bidsBox, asksBox, holdingsBox;
    private TableView<TradeRow> tradeTable;
    private LineChart<String, Number> priceChart;
    private BarChart<String, Number> volumeChart;
    private TextArea activityLog;
    private ScrollPane dashboardScroll;
    private HBox dashboardContent;

    private record Resp(int status, Object json) {
        boolean ok() { return status >= 200 && status < 300; }
    }
    private record TradeRow(String time, String price, String quantity, String buyer, String seller, boolean up) {}
    private record RawTrade(Instant at, double price, long quantity, String buyer, String seller) {}

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(buildHeader());
        root.setLeft(buildSidebar());

        VBox marketColumn = buildMarketColumn();
        VBox actionColumn = buildActionColumn();
        dashboardContent = new HBox(16, marketColumn, actionColumn);
        dashboardContent.setPadding(new Insets(18));
        dashboardContent.setMinWidth(1260);
        HBox.setHgrow(marketColumn, Priority.ALWAYS);
        marketColumn.setMinWidth(760);
        actionColumn.setPrefWidth(330);
        actionColumn.setMinWidth(310);

        dashboardScroll = new ScrollPane(dashboardContent);
        dashboardScroll.setFitToHeight(false);
        dashboardScroll.setFitToWidth(false);
        dashboardScroll.setPannable(true);
        dashboardScroll.getStyleClass().add("dashboard-scroll");
        root.setCenter(dashboardScroll);

        noticeLabel = new Label();
        noticeLabel.getStyleClass().add("notice");
        noticeLabel.setWrapText(true);
        noticeLabel.setVisible(false);
        noticeLabel.setManaged(false);
        VBox bottom = new VBox(noticeLabel);
        bottom.setPadding(new Insets(0, 18, 10, 18));
        root.setBottom(bottom);

        Scene scene = new Scene(root, 1540, 980);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/exchange-dark.css"), "Missing /exchange-dark.css").toExternalForm());
        stage.setTitle("Nexus Exchange | JavaFX Trading Terminal");
        stage.setMinWidth(1250);
        stage.setMinHeight(780);
        stage.setScene(scene);
        stage.show();
        connect();
    }

    private Node buildHeader() {
        Label mark = new Label("◆");
        mark.getStyleClass().add("brand-mark");
        Label title = new Label("NEXUS EXCHANGE");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("JAVA STOCK EXCHANGE  /  PRICE-TIME PRIORITY");
        subtitle.getStyleClass().add("brand-subtitle");
        VBox brand = new VBox(3, new HBox(8, mark, title), subtitle);
        brand.setMinWidth(270);

        urlField = new TextField(preferences.get("url", DEFAULT_URL));
        urlField.setPromptText("http://localhost:8080");
        urlField.setPrefColumnCount(23);
        urlField.setPrefWidth(275);
        urlField.setOnAction(e -> connect());
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("API key (if enabled)");
        apiKeyField.setPrefWidth(180);
        apiKeyField.setOnAction(e -> connect());
        Button connectButton = new Button("Connect");
        connectButton.getStyleClass().add("primary");
        connectButton.setOnAction(e -> connect());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox connection = new HBox(9, urlField, apiKeyField, connectButton);
        connection.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(connection, Priority.NEVER);
        VBox statusStack = new VBox(7);
        serverStatus = statusPill("API · CONNECTING", "status-idle");
        databaseStatus = statusPill("MYSQL · CHECKING", "status-idle");
        simulatorStatus = statusPill("SIMULATOR · CHECKING", "status-idle");
        statusStack.getChildren().addAll(new HBox(7, serverStatus, databaseStatus), new HBox(7, simulatorStatus));
        statusStack.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(18, brand, spacer, connection, statusStack);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 18, 14, 20));
        row.getStyleClass().add("top-header");
        return row;
    }

    private Node buildSidebar() {
        Label sectionLabel = new Label("WORKSPACE");
        sectionLabel.getStyleClass().add("sidebar-section-label");
        VBox nav = new VBox(7,
                navButton("◫", "Trading overview", 0),
                navButton("⌁", "Market chart", 0.18),
                navButton("▤", "Order book", 0.40),
                navButton("⇄", "Recent trades", 0.68),
                navButton("◎", "Order ticket", 0.0),
                navButton("▥", "Simulator & latency", 1.0));
        VBox.setVgrow(nav, Priority.NEVER);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox healthCard = new VBox(7,
                new Label("SYSTEM INTEGRITY"),
                new Label("Transactional persistence"),
                new Label("Java 21  ·  MySQL 8"));
        healthCard.getStyleClass().add("sidebar-health");
        healthCard.getChildren().get(0).getStyleClass().add("sidebar-section-label");
        healthCard.getChildren().get(1).getStyleClass().add("sidebar-health-title");
        healthCard.getChildren().get(2).getStyleClass().add("muted");
        VBox side = new VBox(17, sectionLabel, nav, grow, healthCard);
        side.setPadding(new Insets(23, 13, 17, 13));
        side.setPrefWidth(206);
        side.setMinWidth(206);
        side.getStyleClass().add("sidebar");
        return side;
    }

    private Button navButton(String glyph, String label, double target) {
        Button b = new Button(glyph + "    " + label);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.getStyleClass().add("nav-button");
        if (label.equals("Trading overview")) b.getStyleClass().add("nav-selected");
        b.setOnAction(e -> {
            if (label.equals("Order ticket")) jumpTo(priceField);
            else if (label.equals("Order book")) jumpTo(bidsBox);
            else if (label.equals("Recent trades")) jumpTo(tradeTable);
            else if (label.equals("Market chart")) jumpTo(priceChart);
            else if (label.equals("Simulator & latency")) jumpTo(p50Bar);
            else dashboardScroll.setVvalue(0);
        });
        return b;
    }

    private void jumpTo(Node target) {
        if (target == null || dashboardContent == null || dashboardScroll == null) return;
        Platform.runLater(() -> {
            dashboardContent.applyCss();
            dashboardContent.layout();
            double contentHeight = dashboardContent.getBoundsInLocal().getHeight();
            double viewportHeight = dashboardScroll.getViewportBounds().getHeight();
            if (contentHeight <= viewportHeight) return;
            double y = target.localToScene(0, 0).getY() - dashboardContent.localToScene(0, 0).getY();
            dashboardScroll.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewportHeight))));
        });
    }

    private VBox buildMarketColumn() {
        Label pageTitle = new Label("Trading overview");
        pageTitle.getStyleClass().add("page-title");
        Label pageSubtitle = new Label("Live exchange activity, market liquidity and execution health.");
        pageSubtitle.getStyleClass().add("muted");
        HBox pageHead = new HBox(14, new VBox(4, pageTitle, pageSubtitle));
        pageHead.setAlignment(Pos.CENTER_LEFT);

        symbolBox = new ComboBox<>(FXCollections.observableArrayList(SYMBOLS));
        symbolBox.setValue(selectedSymbol);
        symbolBox.setPrefWidth(175);
        symbolBox.setOnAction(e -> {
            if (symbolBox.getValue() != null && !symbolBox.getValue().equals(selectedSymbol)) {
                selectedSymbol = symbolBox.getValue();
                refresh();
            }
        });
        Label symLabel = new Label("ACTIVE SYMBOL");
        symLabel.getStyleClass().add("small-overline");
        VBox symBox = new VBox(5, symLabel, symbolBox);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        pageHead.getChildren().addAll(gap, symBox);

        HBox metrics = new HBox(10,
                metricCard("ORDERS SUBMITTED", ordersMetric = metricValue("—"), "Simulator totals", "accent-blue"),
                metricCard("TRADES EXECUTED", tradesMetric = metricValue("—"), "Matched executions", "accent-green"),
                metricCard("SHARES TRADED", volumeMetric = metricValue("—"), "Executed quantity", "accent-gold"),
                metricCard("REJECTED ORDERS", rejectsMetric = metricValue("—"), "Simulator errors", "accent-red"));
        for (Node n : metrics.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);

        VBox quoteCard = new VBox(7);
        quoteCard.getStyleClass().add("quote-card");
        Label quoteSymbol = label(selectedSymbol + " · LAST TRADE", "quote-symbol");
        lastPriceLabel = label("—", "quote-price");
        changeLabel = label("Waiting for executed trades", "muted");
        quoteCard.getChildren().addAll(quoteSymbol, lastPriceLabel, changeLabel);
        symbolBox.valueProperty().addListener((obs, before, after) -> quoteSymbol.setText(after + " · LAST TRADE"));

        VBox chartCard = new VBox(10);
        chartCard.getStyleClass().add("panel-card");
        Label chartTitle = label("Price history", "card-title");
        Label chartSub = label("Recent actual executions · no synthetic prices", "muted");
        CategoryAxis priceXAxis = new CategoryAxis();
        priceXAxis.setLabel("Trade time");
        NumberAxis priceYAxis = new NumberAxis();
        priceYAxis.setLabel("Execution price");
        priceYAxis.setForceZeroInRange(false);
        priceChart = new LineChart<>(priceXAxis, priceYAxis);
        priceChart.setLegendVisible(false);
        priceChart.setAnimated(false);
        priceChart.setCreateSymbols(false);
        priceChart.setMinHeight(275);
        priceChart.setPrefHeight(315);
        priceChart.setVerticalGridLinesVisible(false);
        priceChart.setHorizontalGridLinesVisible(true);
        chartEmptyLabel = label("No executed trades for this symbol yet.", "chart-empty");
        chartEmptyLabel.setMouseTransparent(true);
        StackPane chartFrame = new StackPane(priceChart, chartEmptyLabel);
        chartFrame.setMinHeight(285);
        chartCard.getChildren().addAll(new VBox(4, chartTitle, chartSub), chartFrame);

        HBox lower = new HBox(12, buildOrderBookCard(), buildVolumeCard());
        HBox.setHgrow(lower.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(lower.getChildren().get(1), Priority.ALWAYS);

        VBox tapeCard = buildTradeTapeCard();
        VBox body = new VBox(15, pageHead, quoteCard, metrics, chartCard, lower, tapeCard);
        body.setFillWidth(true);
        return body;
    }

    private Node metricCard(String title, Label value, String note, String accent) {
        VBox card = new VBox(8, label(title, "metric-title"), value, label(note, "metric-note"));
        card.getStyleClass().addAll("metric-card", accent);
        card.setMinHeight(104);
        return card;
    }

    private Label metricValue(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("metric-value");
        return l;
    }

    private VBox buildOrderBookCard() {
        Label title = label("Market depth", "card-title");
        spreadLabel = label("Spread —   ·   Mid —", "spread-pill");
        HBox headings = new HBox(8, label("PRICE", "table-overline"), label("QTY", "table-overline"), label("TOTAL", "table-overline"));
        HBox.setHgrow(headings.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(headings.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(headings.getChildren().get(2), Priority.ALWAYS);
        headings.getChildren().get(0).setStyle("-fx-text-fill: #35d39a;");
        VBox bidSide = new VBox(3);
        VBox askSide = new VBox(3);
        bidsBox = bidSide;
        asksBox = askSide;
        bidSide.getChildren().add(label("Waiting for order book data…", "muted"));
        askSide.getChildren().add(label("Waiting for order book data…", "muted"));
        VBox card = new VBox(9, title, headings, spreadLabel, label("ASKS · SELL LIQUIDITY", "book-section-ask"), askSide,
                separator(), label("BIDS · BUY LIQUIDITY", "book-section-bid"), bidSide);
        card.getStyleClass().add("panel-card");
        card.setMinWidth(340);
        return card;
    }

    private VBox buildVolumeCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("panel-card");
        Label title = label("Executed volume", "card-title");
        Label subtitle = label("Quantity per recent trade", "muted");
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        volumeChart = new BarChart<>(x, y);
        volumeChart.setLegendVisible(false);
        volumeChart.setAnimated(false);
        volumeChart.setCategoryGap(8);
        volumeChart.setBarGap(2);
        volumeChart.setMinHeight(235);
        volumeChart.setPrefHeight(260);
        volumeChart.setVerticalGridLinesVisible(false);
        card.getChildren().addAll(new VBox(4, title, subtitle), volumeChart);
        return card;
    }

    private VBox buildTradeTapeCard() {
        Label title = label("Recent trades", "card-title");
        Label sub = label("Newest executions first · refreshes automatically", "muted");
        tradeTable = new TableView<>();
        tradeTable.setPlaceholder(label("No executions yet for the selected symbol.", "muted"));
        tradeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tradeTable.setPrefHeight(250);
        tradeTable.setMinHeight(180);
        tradeTable.getColumns().addAll(
                tradeColumn("TIME", TradeRow::time, 100),
                tradeColumn("PRICE", TradeRow::price, 130),
                tradeColumn("QUANTITY", TradeRow::quantity, 100),
                tradeColumn("BUYER ID", TradeRow::buyer, 110),
                tradeColumn("SELLER ID", TradeRow::seller, 110));
        VBox card = new VBox(10, new VBox(4, title, sub), tradeTable);
        card.getStyleClass().add("panel-card");
        VBox.setVgrow(tradeTable, Priority.ALWAYS);
        return card;
    }

    private TableColumn<TradeRow, String> tradeColumn(String heading, java.util.function.Function<TradeRow, String> getter, double width) {
        TableColumn<TradeRow, String> c = new TableColumn<>(heading);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(getter.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        if (heading.equals("PRICE")) {
            c.setCellFactory(col -> new TableCell<>() {
                @Override protected void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    getStyleClass().removeAll("up", "down");
                    setText(empty ? null : value);
                    TradeRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (!empty && row != null) getStyleClass().add(row.up() ? "up" : "down");
                }
            });
        }
        return c;
    }

    private VBox buildActionColumn() {
        VBox ticket = buildOrderTicket();
        VBox account = buildAccountCard();
        VBox sim = buildSimulatorCard();
        VBox latency = buildLatencyCard();
        VBox log = buildLogCard();
        VBox side = new VBox(14, ticket, account, sim, latency, log);
        side.setPrefWidth(330);
        side.setMinWidth(310);
        return side;
    }

    private VBox buildOrderTicket() {
        Label title = label("Place an order", "card-title");
        userIdField = new TextField("1001");
        userIdField.setPromptText("Trader ID");
        userIdField.textProperty().addListener((o, a, b) -> userIdSnapshot = b == null ? "" : b.trim());

        ToggleGroup sideGroup = new ToggleGroup();
        buyToggle = new ToggleButton("BUY");
        sellToggle = new ToggleButton("SELL");
        buyToggle.setToggleGroup(sideGroup); sellToggle.setToggleGroup(sideGroup);
        buyToggle.setSelected(true);
        buyToggle.getStyleClass().addAll("side-toggle", "buy-toggle");
        sellToggle.getStyleClass().addAll("side-toggle", "sell-toggle");
        buyToggle.setMaxWidth(Double.MAX_VALUE); sellToggle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(buyToggle, Priority.ALWAYS); HBox.setHgrow(sellToggle, Priority.ALWAYS);
        sideGroup.selectedToggleProperty().addListener((o, old, selected) -> {
            if (selected == null) old.setSelected(true);
            else selectedSide = selected == buyToggle ? "BUY" : "SELL";
            updateOrderButton();
        });

        orderTypeBox = new ChoiceBox<>(FXCollections.observableArrayList("LIMIT", "MARKET"));
        orderTypeBox.setValue("LIMIT");
        orderTypeBox.setMaxWidth(Double.MAX_VALUE);
        priceField = new TextField();
        priceField.setPromptText("Limit price");
        quantityField = new TextField("10");
        quantityField.setPromptText("Whole-share quantity");
        orderTypeBox.valueProperty().addListener((o, old, type) -> {
            priceField.setDisable("MARKET".equals(type));
            updateOrderValue();
        });
        priceField.textProperty().addListener((o, a, b) -> updateOrderValue());
        quantityField.textProperty().addListener((o, a, b) -> updateOrderValue());
        orderValueLabel = label("Estimated order value: —", "muted");
        sendOrderButton = new Button("Place buy order");
        sendOrderButton.getStyleClass().add("primary-order");
        sendOrderButton.setMaxWidth(Double.MAX_VALUE);
        sendOrderButton.setOnAction(e -> sendOrder());
        orderFeedback = label("Orders are persisted by the server before acknowledgement.", "order-feedback");
        orderFeedback.setWrapText(true);

        VBox card = new VBox(11, title, new HBox(8, buyToggle, sellToggle),
                field("TRADER ID", userIdField), field("ORDER TYPE", orderTypeBox),
                field("LIMIT PRICE", priceField), field("QUANTITY", quantityField),
                orderValueLabel, sendOrderButton, orderFeedback);
        card.getStyleClass().add("panel-card");
        updateOrderButton();
        return card;
    }

    private VBox buildAccountCard() {
        cashLabel = metricValue("—"); reservedLabel = metricValue("—"); availableLabel = metricValue("—");
        holdingsBox = new VBox(5);
        VBox card = new VBox(9, label("Account overview", "card-title"),
                keyValue("Total cash", cashLabel), keyValue("Reserved cash", reservedLabel),
                keyValue("Available cash", availableLabel), separator(),
                label("POSITIONS", "table-overline"), holdingsBox);
        card.getStyleClass().add("panel-card");
        return card;
    }

    private VBox buildSimulatorCard() {
        Button start = simulatorButton("Start", "start");
        Button pause = simulatorButton("Pause", "pause");
        Button resume = simulatorButton("Resume", "resume");
        Button stop = simulatorButton("Stop", "stop");
        HBox row1 = new HBox(6, start, pause);
        HBox row2 = new HBox(6, resume, stop);
        for (Button button : List.of(start, pause, resume, stop)) { button.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(button, Priority.ALWAYS); }
        VBox card = new VBox(9, label("Simulator controls", "card-title"),
                label("Control automated order generation", "muted"), row1, row2,
                separator(), keyValue("Orders / second", ordersPerSecondLabel = metricValue("—")));
        card.getStyleClass().add("panel-card");
        return card;
    }

    private VBox buildLatencyCard() {
        p50Label = metricValue("—"); p95Label = metricValue("—"); p99Label = metricValue("—");
        p50Bar = latencyBar(); p95Bar = latencyBar(); p99Bar = latencyBar();
        VBox card = new VBox(10, label("Execution latency", "card-title"),
                label("Reported by the live simulator metrics endpoint", "muted"),
                latencyRow("P50", p50Bar, p50Label), latencyRow("P95", p95Bar, p95Label),
                latencyRow("P99", p99Bar, p99Label));
        card.getStyleClass().add("panel-card");
        return card;
    }

    private VBox buildLogCard() {
        activityLog = new TextArea();
        activityLog.setEditable(false);
        activityLog.setWrapText(true);
        activityLog.setPrefRowCount(5);
        activityLog.setPromptText("Connection, order and simulator events will appear here.");
        activityLog.getStyleClass().add("activity-log");
        VBox card = new VBox(8, label("Activity log", "card-title"), activityLog);
        card.getStyleClass().add("panel-card");
        return card;
    }

    private Button simulatorButton(String text, String action) {
        Button b = new Button(text);
        b.setOnAction(e -> call("POST", "/simulator/" + action, "").whenComplete((resp, error) -> Platform.runLater(() -> {
            if (error != null) { log("Simulator " + action + " failed: " + safeMessage(error)); return; }
            if (resp.status() == 503) showNotice("The simulator is not configured on this server. Start it with SIMULATOR_ENABLED=true in the server environment.");
            else if (resp.status() == 401) showNotice("The API rejected the request. Check the API key and reconnect.");
            else if (!resp.ok()) showNotice("Simulator request returned HTTP " + resp.status() + ".");
            else { showNotice(null); log("Simulator command: " + action + " accepted."); }
            refresh();
        })));
        return b;
    }

    // ── REST client ──────────────────────────────────────────────────────────
    private void connect() {
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        if (url.isBlank()) url = DEFAULT_URL;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        baseUrl = url;
        apiKey = apiKeyField.getText() == null ? "" : apiKeyField.getText().trim();
        preferences.put("url", url);
        setStatus(serverStatus, "API · CONNECTING", "status-warn");
        log("Connecting to " + url);
        if (poller != null) poller.shutdownNow();
        poller = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "exchange-ui-poller"); t.setDaemon(true); return t; });
        poller.scheduleWithFixedDelay(this::refresh, 0, 1500, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Resp> call(String method, String path, String body) {
        try {
            String url = baseUrl;
            if (url == null) return CompletableFuture.failedFuture(new IllegalStateException("Connect to an API server first."));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);
            return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> new Resp(response.statusCode(), Json.parse(response.body())));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void refresh() {
        if (baseUrl == null || !refreshing.compareAndSet(false, true)) return;
        String sym = selectedSymbol;
        String user = userIdSnapshot;
        CompletableFuture<Resp> ready = call("GET", "/ready", null);
        CompletableFuture<Resp> simStatus = call("GET", "/simulator/status", null);
        CompletableFuture<Resp> metrics = call("GET", "/simulator/metrics", null);
        CompletableFuture<Resp> book = call("GET", "/orderbook/" + sym, null);
        CompletableFuture<Resp> tape = call("GET", "/trades?symbol=" + sym + "&limit=60", null);
        CompletableFuture<Resp> account = user.matches("\\d+") ? call("GET", "/accounts/" + user, null)
                : CompletableFuture.completedFuture(new Resp(0, null));
        CompletableFuture.allOf(ready, simStatus, metrics, book, tape, account).whenComplete((unused, error) -> {
            refreshing.set(false);
            Platform.runLater(() -> {
                if (error != null) {
                    setStatus(serverStatus, "API · OFFLINE", "status-bad");
                    showNotice("The API did not respond. If this is a free Render service, it may be waking from sleep; reconnecting and polling will continue automatically.");
                    return;
                }
                apply(ready.join(), simStatus.join(), metrics.join(), book.join(), tape.join(), account.join(), sym);
            });
        });
    }

    private void apply(Resp ready, Resp simStatus, Resp metrics, Resp book, Resp trades, Resp account, String symbolAtRequest) {
        setStatus(serverStatus, ready.ok() ? "API · ONLINE" : "API · CHECK", ready.ok() ? "status-ok" : "status-warn");
        Map<String, Object> readyMap = Json.map(ready.json());
        String mode = readyMap == null ? "" : String.valueOf(readyMap.getOrDefault("mode", ""));
        setStatus(databaseStatus, "persistent".equals(mode) ? "MYSQL · CONNECTED" : "DB · " + (mode.isBlank() ? "UNKNOWN" : mode.toUpperCase(Locale.ROOT)),
                "persistent".equals(mode) ? "status-ok" : "status-warn");

        if (book.status() == 401 || trades.status() == 401 || account.status() == 401) {
            setStatus(simulatorStatus, "AUTH · REQUIRED", "status-warn");
            showNotice("The API requires a key. Enter it in the header and press Connect.");
            return;
        }
        showNotice(null);
        Map<String, Object> statusMap = Json.map(simStatus.json());
        if (simStatus.ok() && statusMap != null) {
            boolean running = Boolean.TRUE.equals(statusMap.get("running"));
            boolean paused = Boolean.TRUE.equals(statusMap.get("paused"));
            setStatus(simulatorStatus, running ? (paused ? "SIMULATOR · PAUSED" : "SIMULATOR · RUNNING") : "SIMULATOR · STOPPED",
                    running && !paused ? "status-ok" : paused ? "status-warn" : "status-idle");
        } else {
            setStatus(simulatorStatus, simStatus.status() == 503 ? "SIMULATOR · DISABLED" : "SIMULATOR · CHECK", "status-idle");
        }
        if (metrics.ok()) renderMetrics(Json.map(metrics.json()));
        if (!symbolAtRequest.equals(selectedSymbol)) return;
        if (book.ok()) renderBook(Json.map(book.json()));
        if (trades.ok()) renderTrades(Json.map(trades.json()));
        if (account.ok()) renderAccount(Json.map(account.json()));
        else if (account.status() == 404 || account.status() == 422) {
            cashLabel.setText("Not found"); reservedLabel.setText("—"); availableLabel.setText("—"); holdingsBox.getChildren().clear();
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────
    private void renderMetrics(Map<String, Object> m) {
        if (m == null) return;
        ordersMetric.setText(INTEGER.format(Json.num(m.get("ordersSubmitted"))));
        tradesMetric.setText(INTEGER.format(Json.num(m.get("tradesExecuted"))));
        volumeMetric.setText(INTEGER.format(Json.num(m.get("executedVolume"))));
        double errors = Json.num(m.get("errorsCount"));
        rejectsMetric.setText(INTEGER.format(errors));
        rejectsMetric.getStyleClass().removeAll("metric-danger", "metric-normal");
        rejectsMetric.getStyleClass().add(errors > 0 ? "metric-danger" : "metric-normal");
        ordersPerSecondLabel.setText(String.format(Locale.ROOT, "%.2f", Json.num(m.get("ordersPerSecond"))));
        double p50 = Json.num(m.get("latencyP50Micros"));
        double p95 = Json.num(m.get("latencyP95Micros"));
        double p99 = Json.num(m.get("latencyP99Micros"));
        p50Label.setText(formatLatency(p50)); p95Label.setText(formatLatency(p95)); p99Label.setText(formatLatency(p99));
        double max = Math.max(1, Math.max(p50, Math.max(p95, p99)));
        p50Bar.setProgress(p50 / max); p95Bar.setProgress(p95 / max); p99Bar.setProgress(p99 / max);
    }

    private String formatLatency(double micros) {
        if (micros <= 0) return "—";
        if (micros >= 1_000_000) return String.format(Locale.ROOT, "%.2f s", micros / 1_000_000);
        if (micros >= 1_000) return String.format(Locale.ROOT, "%.1f ms", micros / 1_000);
        return Math.round(micros) + " µs";
    }

    private void renderBook(Map<String, Object> book) {
        if (book == null) return;
        List<double[]> bids = parseLevels(book.get("bids"));
        List<double[]> asks = parseLevels(book.get("asks"));
        bids.sort((a, b) -> Double.compare(b[0], a[0]));
        asks.sort(Comparator.comparingDouble(a -> a[0]));
        if (bids.size() > BOOK_DEPTH) bids = new ArrayList<>(bids.subList(0, BOOK_DEPTH));
        if (asks.size() > BOOK_DEPTH) asks = new ArrayList<>(asks.subList(0, BOOK_DEPTH));
        asksBox.getChildren().clear(); bidsBox.getChildren().clear();
        if (asks.isEmpty()) asksBox.getChildren().add(label("No resting sell orders", "muted"));
        else {
            double cumulative = 0;
            for (double[] level : asks) { cumulative += level[1]; asksBox.getChildren().add(bookLevel(level[0], level[1], cumulative, true)); }
        }
        if (bids.isEmpty()) bidsBox.getChildren().add(label("No resting buy orders", "muted"));
        else {
            double cumulative = 0;
            for (double[] level : bids) { cumulative += level[1]; bidsBox.getChildren().add(bookLevel(level[0], level[1], cumulative, false)); }
        }
        if (!asks.isEmpty() && !bids.isEmpty()) {
            double spread = asks.get(0)[0] - bids.get(0)[0];
            double mid = (asks.get(0)[0] + bids.get(0)[0]) / 2;
            spreadLabel.setText("SPREAD  " + MONEY.format(spread) + "     ·     MID  " + MONEY.format(mid));
        } else spreadLabel.setText("One-sided book · spread unavailable");
    }

    private Node bookLevel(double price, double quantity, double cumulativeTotal, boolean ask) {
        Label p = label(MONEY.format(price), ask ? "ask-text" : "bid-text");
        Label q = label(INTEGER.format(quantity), "book-quantity");
        Label total = label(INTEGER.format(cumulativeTotal), "muted");
        HBox row = new HBox(6, p, q, total);
        row.setAlignment(Pos.CENTER_LEFT);
        for (Node n : row.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);
        q.setAlignment(Pos.CENTER_RIGHT); total.setAlignment(Pos.CENTER_RIGHT);
        row.getStyleClass().add("book-level");
        row.setOnMouseClicked(e -> {
            if (priceField != null && !"MARKET".equals(orderTypeBox.getValue())) priceField.setText(String.format(Locale.ROOT, "%.2f", price));
        });
        row.setOnMouseEntered(e -> row.getStyleClass().add("book-level-hover"));
        row.setOnMouseExited(e -> row.getStyleClass().remove("book-level-hover"));
        Tooltip.install(row, new Tooltip("Click to set the limit price to " + MONEY.format(price)));
        return row;
    }

    private List<double[]> parseLevels(Object object) {
        List<double[]> values = new ArrayList<>();
        for (Object item : Json.list(object)) {
            Map<String, Object> row = Json.map(item);
            if (row != null) values.add(new double[]{Json.num(row.get("price")), Json.num(row.get("quantity"))});
        }
        return values;
    }

    private void renderTrades(Map<String, Object> data) {
        if (data == null) return;
        List<RawTrade> raw = new ArrayList<>();
        for (Object item : Json.list(data.get("trades"))) {
            Map<String, Object> row = Json.map(item);
            if (row == null) continue;
            Instant at;
            try { at = Instant.parse(String.valueOf(row.get("executedAt"))); }
            catch (Exception e) { at = Instant.EPOCH; }
            raw.add(new RawTrade(at, Json.num(row.get("price")), (long) Json.num(row.get("quantity")),
                    String.valueOf((long) Json.num(row.get("buyUserId"))), String.valueOf((long) Json.num(row.get("sellUserId")))));
        }
        raw.sort(Comparator.comparing(RawTrade::at));
        List<TradeRow> tableRows = new ArrayList<>();
        for (int i = raw.size() - 1; i >= 0; i--) {
            RawTrade current = raw.get(i);
            boolean up = i == 0 || current.price() >= raw.get(i - 1).price();
            tableRows.add(new TradeRow(current.at().atZone(ZoneId.systemDefault()).format(TIME), MONEY.format(current.price()),
                    INTEGER.format(current.quantity()), current.buyer(), current.seller(), up));
        }
        tradeTable.getItems().setAll(tableRows);
        updatePriceChart(raw);
        updateVolumeChart(raw);
        if (raw.isEmpty()) {
            lastPriceLabel.setText("—");
            changeLabel.setText("No executed trades for " + selectedSymbol);
            changeLabel.getStyleClass().removeAll("up", "down");
            return;
        }
        RawTrade first = raw.get(0), last = raw.get(raw.size() - 1);
        double delta = last.price() - first.price();
        lastPriceLabel.setText(MONEY.format(last.price()));
        changeLabel.setText(String.format(Locale.ROOT, "%s%s  (%.2f%%) across %d recent trades", delta >= 0 ? "+" : "", MONEY.format(delta),
                first.price() == 0 ? 0 : delta / first.price() * 100, raw.size()));
        changeLabel.getStyleClass().removeAll("up", "down"); changeLabel.getStyleClass().add(delta >= 0 ? "up" : "down");
    }

    private void updatePriceChart(List<RawTrade> trades) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        int start = Math.max(0, trades.size() - 40);
        for (int i = start; i < trades.size(); i++) {
            RawTrade trade = trades.get(i);
            String label = trade.at().atZone(ZoneId.systemDefault()).format(TIME);
            series.getData().add(new XYChart.Data<>(label, trade.price()));
        }
        priceChart.getData().setAll(series);
        chartEmptyLabel.setVisible(series.getData().isEmpty());
        if (priceChart.getData().size() > 0) {
            priceChart.lookupAll(".chart-series-line").forEach(n -> n.setStyle("-fx-stroke: #35d39a; -fx-stroke-width: 2.2px;"));
        }
    }

    private void updateVolumeChart(List<RawTrade> trades) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        int start = Math.max(0, trades.size() - 8);
        for (int i = start; i < trades.size(); i++) {
            RawTrade trade = trades.get(i);
            String label = trade.at().atZone(ZoneId.systemDefault()).format(TIME);
            series.getData().add(new XYChart.Data<>(label, trade.quantity()));
        }
        volumeChart.getData().setAll(series);
    }

    private void renderAccount(Map<String, Object> account) {
        if (account == null) return;
        cashLabel.setText(MONEY.format(Json.num(account.get("totalCash"))));
        reservedLabel.setText(MONEY.format(Json.num(account.get("reservedCash"))));
        availableLabel.setText(MONEY.format(Json.num(account.get("availableCash"))));
        holdingsBox.getChildren().clear();
        Map<String, Object> positions = Json.map(account.get("positions"));
        if (positions == null || positions.isEmpty()) {
            holdingsBox.getChildren().add(label("No positions held", "muted"));
            return;
        }
        positions.forEach((symbol, quantity) -> holdingsBox.getChildren().add(keyValue(symbol, metricValue(INTEGER.format(Json.num(quantity)) + " sh"))));
    }

    // ── Order actions ─────────────────────────────────────────────────────────
    private void updateOrderButton() {
        if (sendOrderButton == null) return;
        sendOrderButton.setText(selectedSide.equals("BUY") ? "Place buy order" : "Place sell order");
        sendOrderButton.getStyleClass().removeAll("buy-order", "sell-order");
        sendOrderButton.getStyleClass().add(selectedSide.equals("BUY") ? "buy-order" : "sell-order");
    }

    private void updateOrderValue() {
        if (orderValueLabel == null || quantityField == null || orderTypeBox == null) return;
        try {
            long qty = Long.parseLong(quantityField.getText().trim());
            if (qty <= 0) throw new NumberFormatException();
            if ("MARKET".equals(orderTypeBox.getValue())) orderValueLabel.setText("Market order · executes at available book prices");
            else {
                double price = Double.parseDouble(priceField.getText().trim());
                if (price <= 0) throw new NumberFormatException();
                orderValueLabel.setText("Estimated value   " + MONEY.format(price * qty));
            }
        } catch (Exception e) { orderValueLabel.setText("Estimated order value: —"); }
    }

    private void sendOrder() {
        orderFeedback.getStyleClass().removeAll("feedback-ok", "feedback-error");
        long userId, quantity;
        try {
            userId = Long.parseLong(userIdField.getText().trim());
            quantity = Long.parseLong(quantityField.getText().trim());
            if (userId <= 0 || quantity <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            feedback("Trader ID and quantity must be positive whole numbers.", false); return;
        }
        String type = orderTypeBox.getValue();
        String pricePart = "";
        if ("LIMIT".equals(type)) {
            try {
                double price = Double.parseDouble(priceField.getText().trim());
                if (!Double.isFinite(price) || price <= 0) throw new NumberFormatException();
                pricePart = ",\"price\":" + String.format(Locale.ROOT, "%.2f", price);
            } catch (NumberFormatException e) { feedback("Enter a positive limit price.", false); return; }
        }
        String body = "{\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"userId\":" + userId +
                ",\"symbol\":\"" + selectedSymbol + "\",\"side\":\"" + selectedSide + "\",\"type\":\"" + type +
                "\",\"quantity\":" + quantity + pricePart + "}";
        sendOrderButton.setDisable(true);
        feedback("Submitting order to exchange…", true);
        String description = selectedSide + " " + quantity + " " + selectedSymbol + ("LIMIT".equals(type) ? " @ " + priceField.getText().trim() : " @ MARKET");
        call("POST", "/orders", body).whenComplete((resp, error) -> Platform.runLater(() -> {
            sendOrderButton.setDisable(false);
            if (error != null) {
                feedback("Order request failed: " + safeMessage(error) + ". Check the book before retrying.", false);
                log(description + " -> network error"); return;
            }
            Map<String, Object> json = Json.map(resp.json());
            if (resp.ok() && json != null) {
                int tradeCount = Json.list(json.get("trades")).size();
                String message = tradeCount > 0 ? "Filled · " + tradeCount + (tradeCount == 1 ? " trade executed." : " trades executed.")
                        : "Accepted · order is resting in the book.";
                feedback(message, true);
                log(description + " -> " + message);
            } else {
                String message = json != null && json.get("error") != null ? String.valueOf(json.get("error"))
                        : resp.status() == 401 ? "API key missing or incorrect." : "Rejected (HTTP " + resp.status() + ").";
                feedback(message, false); log(description + " -> rejected: " + message);
            }
            refresh();
        }));
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private Label statusPill(String text, String style) {
        Label label = new Label(text); label.getStyleClass().addAll("status-pill", style); return label;
    }
    private void setStatus(Label target, String text, String style) {
        if (target == null) return;
        target.setText(text); target.getStyleClass().removeAll("status-ok", "status-warn", "status-bad", "status-idle"); target.getStyleClass().add(style);
    }
    private Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); return label;
    }
    private VBox field(String caption, Control control) {
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(5, label(caption, "field-caption"), control);
    }
    private HBox keyValue(String key, Label value) {
        Label name = label(key, "muted");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, name, spacer, value); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("key-value");
        return row;
    }
    private Node separator() { Region r = new Region(); r.getStyleClass().add("separator-line"); r.setMinHeight(1); r.setPrefHeight(1); return r; }
    private ProgressBar latencyBar() { ProgressBar p = new ProgressBar(0); p.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(p, Priority.ALWAYS); p.getStyleClass().add("latency-progress"); return p; }
    private HBox latencyRow(String name, ProgressBar bar, Label value) {
        Label n = label(name, "latency-label"); n.setMinWidth(34); value.setMinWidth(68); value.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, n, bar, value); row.setAlignment(Pos.CENTER_LEFT); return row;
    }
    private void feedback(String message, boolean ok) {
        orderFeedback.setText(message);
        orderFeedback.getStyleClass().removeAll("feedback-ok", "feedback-error");
        orderFeedback.getStyleClass().add(ok ? "feedback-ok" : "feedback-error");
    }
    private void showNotice(String message) {
        boolean visible = message != null && !message.isBlank();
        noticeLabel.setText(visible ? message : ""); noticeLabel.setVisible(visible); noticeLabel.setManaged(visible);
    }
    private void log(String message) {
        if (activityLog == null) return;
        activityLog.appendText("[" + java.time.LocalTime.now().format(TIME) + "] " + message + "\n");
        if (activityLog.getLength() > 18_000) activityLog.deleteText(0, 4_000);
    }
    private static String safeMessage(Throwable error) {
        Throwable e = error;
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public void stop() { if (poller != null) poller.shutdownNow(); }
    public static void main(String[] args) { launch(args); }

    // Small dependency-free JSON parser compatible with the project's existing API responses.
    static final class Json {
        private final String source; private int index;
        private Json(String source) { this.source = source; }
        static Object parse(String text) {
            try { return text == null || text.isBlank() ? null : new Json(text).value(); }
            catch (Exception ignored) { return null; }
        }
        @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
        @SuppressWarnings("unchecked") static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
        static double num(Object o) { if (o instanceof Number n) return n.doubleValue(); try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
        private void skipWhitespace() { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; }
        private Object value() {
            skipWhitespace(); if (index >= source.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = source.charAt(index);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>(); index++; skipWhitespace();
                if (source.charAt(index) == '}') { index++; return map; }
                while (true) {
                    skipWhitespace(); String key = string(); skipWhitespace(); index++; map.put(key, value()); skipWhitespace();
                    char delimiter = source.charAt(index++); if (delimiter == '}') return map; if (delimiter != ',') throw new IllegalArgumentException("Invalid object");
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>(); index++; skipWhitespace();
                if (source.charAt(index) == ']') { index++; return list; }
                while (true) { list.add(value()); skipWhitespace(); char delimiter = source.charAt(index++); if (delimiter == ']') return list; if (delimiter != ',') throw new IllegalArgumentException("Invalid array"); }
            }
            if (c == '"') return string();
            if (source.startsWith("true", index)) { index += 4; return Boolean.TRUE; }
            if (source.startsWith("false", index)) { index += 5; return Boolean.FALSE; }
            if (source.startsWith("null", index)) { index += 4; return null; }
            int start = index; while (index < source.length() && "-+0123456789.eE".indexOf(source.charAt(index)) >= 0) index++;
            return Double.parseDouble(source.substring(start, index));
        }
        private String string() {
            if (source.charAt(index++) != '"') throw new IllegalArgumentException("Expected string");
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++); if (c == '"') return out.toString();
                if (c == '\\') {
                    if (index >= source.length()) break;
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"' -> out.append('"'); case '\\' -> out.append('\\'); case '/' -> out.append('/');
                        case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> { out.append((char) Integer.parseInt(source.substring(index, index + 4), 16)); index += 4; }
                        default -> out.append(escaped);
                    }
                } else out.append(c);
            }
            throw new IllegalArgumentException("Unterminated string");
        }
    }
}
