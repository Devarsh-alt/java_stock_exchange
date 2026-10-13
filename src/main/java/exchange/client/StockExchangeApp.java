package exchange.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Polyline;
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
 * JavaFX trading terminal for the exchange REST API (local or deployed on Render).
 * Run with:  mvn -Pui javafx:run
 */
public class StockExchangeApp extends Application {

    private static final List<String> SYMBOLS = List.of("RELIANCE.NS", "TCS.NS", "INFOSYS.NS");
    private static final String DEFAULT_URL = "https://java-stock-exchange-sg-gjfz.onrender.com";
    private static final int DEPTH = 8;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat INT = new DecimalFormat("#,##0");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---- networking ----
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private volatile String baseUrl;
    private volatile String apiKey = "";
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private ScheduledExecutorService poller;
    private final Preferences prefs = Preferences.userNodeForPackage(StockExchangeApp.class);

    // ---- state ----
    private String symbol = SYMBOLS.get(0);
    private String side = "BUY";

    // ---- widgets ----
    private TextField urlField, userField, priceField, qtyField;
    private PasswordField keyField;
    private Label serverPill, dbPill, simPill, notice, lastPrice, change, orderMsg, notional, spreadLbl;
    private Label cashLbl, reservedLbl, availLbl;
    private Label mOrders, mTrades, mShares, mOps, mErrors, p50, p95, p99;
    private ProgressBar b50, b95, b99;
    private VBox ladder, positions;
    private Pane spark;
    private TableView<Tr> tape;
    private TextArea logArea;
    private Button sendBtn;

    private record Tr(String time, double price, long qty, String buyer, String seller, boolean up) {}
    private record Resp(int status, Object json) { boolean ok() { return status >= 200 && status < 300; } }

    // =====================================================================
    // UI construction
    // =====================================================================
    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(header());

        VBox left = new VBox(14, symbolBar(), quoteCard(), ladderCard(), tapeCard());
        VBox.setVgrow(left.getChildren().get(3), Priority.ALWAYS);
        left.setPadding(new Insets(14, 7, 7, 14));
        HBox.setHgrow(left, Priority.ALWAYS);

        VBox right = new VBox(14, ticketCard(), accountCard(), simulatorCard());
        right.setPadding(new Insets(14, 14, 7, 7));
        right.setPrefWidth(370);
        right.setMinWidth(340);
        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.getStyleClass().add("flat-scroll");
        rightScroll.setPrefWidth(392);

        root.setCenter(new HBox(left, rightScroll));
        root.setBottom(logCard());

        Scene scene = new Scene(root, 1360, 880);
        scene.getStylesheets().add(getClass().getResource("/exchange-dark.css").toExternalForm());
        stage.setTitle("Stock Exchange Terminal");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();

        connect();
    }

    private Node header() {
        Label title = new Label("Stock Exchange");
        title.getStyleClass().add("app-title");
        Label sub = new Label("Price-time priority matching engine");
        sub.getStyleClass().add("muted");
        VBox titles = new VBox(1, title, sub);

        urlField = new TextField(prefs.get("url", DEFAULT_URL));
        urlField.setPrefWidth(300);
        urlField.setPromptText("API URL");
        keyField = new PasswordField();
        keyField.setPromptText("API key");
        keyField.setPrefWidth(200);
        keyField.setOnAction(e -> connect());
        Button connectBtn = new Button("Connect");
        connectBtn.getStyleClass().add("primary");
        connectBtn.setOnAction(e -> connect());
        urlField.setOnAction(e -> connect());

        serverPill = pill("Server: not connected", "");
        dbPill = pill("Database: -", "");
        simPill = pill("Simulator: -", "");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, titles, spacer, urlField, keyField, connectBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        HBox pills = new HBox(8, serverPill, dbPill, simPill);

        notice = new Label();
        notice.getStyleClass().add("notice");
        notice.setWrapText(true);
        notice.setManaged(false);
        notice.setVisible(false);

        VBox box = new VBox(10, bar, pills, notice);
        box.setPadding(new Insets(14));
        box.getStyleClass().add("header");
        return box;
    }

    private Node symbolBar() {
        ToggleGroup g = new ToggleGroup();
        HBox box = new HBox(8);
        for (String s : SYMBOLS) {
            ToggleButton b = new ToggleButton(s);
            b.setToggleGroup(g);
            b.getStyleClass().add("tab");
            b.setSelected(s.equals(symbol));
            b.setOnAction(e -> {
                symbol = s;
                priceField.clear();
                ladder.getChildren().clear();
                tape.getItems().clear();
                refresh();
            });
            box.getChildren().add(b);
        }
        g.selectedToggleProperty().addListener((o, a, n) -> { if (n == null && a != null) a.setSelected(true); });
        return box;
    }

    private Node quoteCard() {
        lastPrice = new Label("-");
        lastPrice.getStyleClass().add("big-price");
        change = new Label("Waiting for trades");
        change.getStyleClass().add("muted");
        VBox left = new VBox(4, lastPrice, change);
        spark = new Pane();
        spark.setPrefSize(260, 60);
        spark.setMinSize(260, 60);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox h = new HBox(left, sp, spark);
        h.setAlignment(Pos.CENTER_LEFT);
        return card(h);
    }

    private Node ladderCard() {
        ladder = new VBox(2);
        Label t = new Label("Market depth");
        t.getStyleClass().add("card-title");
        Label hint = new Label("Click a price to copy it into the order ticket");
        hint.getStyleClass().add("muted");
        HBox top = new HBox(10, t, hint);
        top.setAlignment(Pos.BASELINE_LEFT);
        return card(new VBox(8, top, ladder));
    }

    @SuppressWarnings("unchecked")
    private Node tapeCard() {
        tape = new TableView<>();
        tape.setPlaceholder(new Label("No trades for this symbol yet. Start the simulator or cross the spread."));
        tape.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Tr, String> time = col("Time", t -> t.time());
        TableColumn<Tr, String> price = col("Price", t -> MONEY.format(t.price()));
        price.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String it, boolean empty) {
                super.updateItem(it, empty);
                getStyleClass().removeAll("up", "down");
                setText(empty ? null : it);
                Tr row = (getTableRow() == null) ? null : getTableRow().getItem();
                if (!empty && row != null) getStyleClass().add(row.up() ? "up" : "down");
            }
        });
        TableColumn<Tr, String> qty = col("Qty", t -> INT.format(t.qty()));
        TableColumn<Tr, String> buyer = col("Buyer", Tr::buyer);
        TableColumn<Tr, String> seller = col("Seller", Tr::seller);
        for (TableColumn<Tr, String> c : List.of(price, qty)) c.setStyle("-fx-alignment: CENTER-RIGHT;");
        tape.getColumns().addAll(time, price, qty, buyer, seller);
        Label t = new Label("Trade tape");
        t.getStyleClass().add("card-title");
        VBox v = new VBox(8, t, tape);
        VBox.setVgrow(tape, Priority.ALWAYS);
        VBox c = card(v);
        c.setMinHeight(180);
        return c;
    }

    private Node ticketCard() {
        userField = new TextField("1001");
        priceField = new TextField();
        priceField.setPromptText("Limit price");
        qtyField = new TextField("10");

        ToggleGroup g = new ToggleGroup();
        ToggleButton buy = new ToggleButton("Buy");
        ToggleButton sell = new ToggleButton("Sell");
        buy.getStyleClass().addAll("seg", "buy");
        sell.getStyleClass().addAll("seg", "sell");
        buy.setToggleGroup(g);
        sell.setToggleGroup(g);
        buy.setSelected(true);
        buy.setMaxWidth(Double.MAX_VALUE);
        sell.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(buy, Priority.ALWAYS);
        HBox.setHgrow(sell, Priority.ALWAYS);
        g.selectedToggleProperty().addListener((o, a, n) -> {
            if (n == null) a.setSelected(true); else side = (n == buy) ? "BUY" : "SELL";
            updateSendButton();
        });

        ChoiceBox<String> type = new ChoiceBox<>(FXCollections.observableArrayList("LIMIT", "MARKET"));
        type.setValue("LIMIT");
        type.setMaxWidth(Double.MAX_VALUE);
        type.valueProperty().addListener((o, a, n) -> { priceField.setDisable("MARKET".equals(n)); updateNotional(n); });
        priceField.textProperty().addListener((o, a, n) -> updateNotional(type.getValue()));
        qtyField.textProperty().addListener((o, a, n) -> updateNotional(type.getValue()));

        notional = new Label("Order value: -");
        notional.getStyleClass().add("muted");
        orderMsg = new Label();
        orderMsg.setWrapText(true);
        sendBtn = new Button("Send order");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setOnAction(e -> sendOrder(type.getValue()));
        updateSendButton();

        Label t = new Label("Place an order");
        t.getStyleClass().add("card-title");
        VBox v = new VBox(8, t,
                new HBox(8, buy, sell),
                field("Trader ID", userField),
                field("Order type", type),
                field("Price", priceField),
                field("Quantity", qtyField),
                notional, sendBtn, orderMsg);
        return card(v);
    }

    private Node accountCard() {
        cashLbl = valueLabel();
        reservedLbl = valueLabel();
        availLbl = valueLabel();
        positions = new VBox(2);
        Label t = new Label("Account (trader ID above)");
        t.getStyleClass().add("card-title");
        Label pt = new Label("Holdings");
        pt.getStyleClass().add("muted");
        return card(new VBox(6, t, kv("Total cash", cashLbl), kv("Reserved", reservedLbl), kv("Available", availLbl), pt, positions));
    }

    private Node simulatorCard() {
        Button start = simBtn("Start", "start"), pause = simBtn("Pause", "pause"),
               resume = simBtn("Resume", "resume"), stop = simBtn("Stop", "stop");
        HBox btns = new HBox(6, start, pause, resume, stop);
        mOrders = valueLabel(); mTrades = valueLabel(); mShares = valueLabel(); mOps = valueLabel(); mErrors = valueLabel();
        p50 = valueLabel(); p95 = valueLabel(); p99 = valueLabel();
        b50 = bar(); b95 = bar(); b99 = bar();
        Label t = new Label("Simulator");
        t.getStyleClass().add("card-title");
        Label lt = new Label("Time to process an order");
        lt.getStyleClass().add("muted");
        Label note = new Label("Each order is committed to MySQL before it is acknowledged.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        return card(new VBox(6, t, btns,
                kv("Orders submitted", mOrders), kv("Trades executed", mTrades), kv("Shares traded", mShares),
                kv("Orders per second", mOps), kv("Rejected orders", mErrors),
                lt, latRow("p50", b50, p50), latRow("p95", b95, p95), latRow("p99", b99, p99), note));
    }

    private Node logCard() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(5);
        logArea.getStyleClass().add("log");
        VBox v = new VBox(4, new Label("Activity log") {{ getStyleClass().add("card-title"); }}, logArea);
        v.setPadding(new Insets(7, 14, 14, 14));
        return v;
    }

    // ---- small widget helpers ----
    private Label pill(String text, String cls) {
        Label l = new Label(text);
        l.getStyleClass().addAll("pill", cls.isEmpty() ? "idle" : cls);
        return l;
    }
    private void setPill(Label l, String cls, String text) {
        l.setText(text);
        l.getStyleClass().removeAll("ok", "warn", "bad", "idle");
        l.getStyleClass().add(cls);
    }
    private VBox card(Node content) {
        VBox v = new VBox(content);
        v.getStyleClass().add("card");
        return v;
    }
    private Label valueLabel() {
        Label l = new Label("-");
        l.getStyleClass().add("value");
        return l;
    }
    private HBox kv(String k, Label v) {
        Label key = new Label(k);
        key.getStyleClass().add("muted");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox h = new HBox(key, sp, v);
        h.getStyleClass().add("kv");
        return h;
    }
    private VBox field(String label, Control c) {
        Label l = new Label(label);
        l.getStyleClass().add("muted");
        if (c instanceof TextField) c.setMaxWidth(Double.MAX_VALUE);
        return new VBox(3, l, c);
    }
    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(0);
        p.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(p, Priority.ALWAYS);
        return p;
    }
    private HBox latRow(String name, ProgressBar b, Label v) {
        Label n = new Label(name);
        n.setMinWidth(34);
        v.setMinWidth(70);
        v.setAlignment(Pos.CENTER_RIGHT);
        HBox h = new HBox(8, n, b, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }
    private Button simBtn(String text, String action) {
        Button b = new Button(text);
        b.setOnAction(e -> {
            call("POST", "/simulator/" + action, "").whenComplete((r, err) -> Platform.runLater(() -> {
                if (err != null) log("Simulator " + action + " failed: " + err.getMessage());
                else if (r.status() == 503) showNotice("The simulator is not enabled on this server. Set SIMULATOR_ENABLED=true and redeploy.");
                else if (r.status() == 401) showNotice("Enter the API key and press Connect.");
                else log("Simulator " + action + ": OK");
                refresh();
            }));
        });
        return b;
    }
    private <T> TableColumn<Tr, String> col(String name, java.util.function.Function<Tr, String> f) {
        TableColumn<Tr, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setSortable(false);
        return c;
    }

    // =====================================================================
    // Networking
    // =====================================================================
    private void connect() {
        String u = urlField.getText().trim();
        if (u.isEmpty()) u = DEFAULT_URL;
        if (!u.startsWith("http")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        baseUrl = u;
        apiKey = keyField.getText().trim();
        prefs.put("url", u);
        setPill(serverPill, "warn", "Server: connecting (free hosts can take ~50s to wake)");
        log("Connecting to " + u);
        if (poller != null) poller.shutdownNow();
        poller = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "poller"); t.setDaemon(true); return t; });
        poller.scheduleWithFixedDelay(this::refresh, 0, 1500, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Resp> call(String method, String path, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (!apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> new Resp(r.statusCode(), Json.parse(r.body())));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void refresh() {
        if (baseUrl == null || !busy.compareAndSet(false, true)) return;
        String sym = symbol;
        String uid = Platform.isFxApplicationThread() ? userField.getText().trim() : userIdSnapshot;
        var ready = call("GET", "/ready", null);
        var status = call("GET", "/simulator/status", null);
        var metrics = call("GET", "/simulator/metrics", null);
        var book = call("GET", "/orderbook/" + sym, null);
        var trades = call("GET", "/trades?symbol=" + sym + "&limit=60", null);
        var acct = uid.matches("\\d+") ? call("GET", "/accounts/" + uid, null) : CompletableFuture.completedFuture(new Resp(0, null));
        CompletableFuture.allOf(ready, status, metrics, book, trades, acct).whenComplete((v, err) -> {
            busy.set(false);
            Platform.runLater(() -> {
                userIdSnapshot = userField.getText().trim();
                if (err != null) {
                    setPill(serverPill, "bad", "Server: not responding");
                    showNotice("The server did not answer. On a free host it sleeps when idle, so the first request can take about a minute. This window retries automatically.");
                    return;
                }
                apply(ready.join(), status.join(), metrics.join(), book.join(), trades.join(), acct.join(), sym);
            });
        });
    }
    private volatile String userIdSnapshot = "1001";

    private void apply(Resp ready, Resp status, Resp metrics, Resp book, Resp trades, Resp acct, String sym) {
        setPill(serverPill, "ok", "Server: online");
        Map<String, Object> rm = Json.map(ready.json());
        if (rm != null) {
            boolean persistent = "persistent".equals(rm.get("mode"));
            setPill(dbPill, persistent ? "ok" : "warn", persistent ? "MySQL: connected" : "In-memory mode, nothing is saved");
        }
        if (book.status() == 401 || metrics.status() == 401) {
            setPill(simPill, "warn", "Key needed");
            showNotice("Enter the API key above and press Connect to load live data.");
            return;
        }
        showNotice(null);

        Map<String, Object> sm = Json.map(status.json());
        if (status.ok() && sm != null) {
            boolean run = Boolean.TRUE.equals(sm.get("running")), pa = Boolean.TRUE.equals(sm.get("paused"));
            if (run && pa) setPill(simPill, "warn", "Simulator: paused");
            else if (run) setPill(simPill, "ok", "Simulator: running");
            else setPill(simPill, "idle", "Simulator: stopped");
        } else if (status.status() == 503) setPill(simPill, "idle", "Simulator: not enabled");

        if (metrics.ok()) renderMetrics(Json.map(metrics.json()));
        if (!sym.equals(symbol)) return; // user switched tab while request was in flight
        if (book.ok()) renderBook(Json.map(book.json()));
        if (trades.ok()) renderTrades(Json.map(trades.json()));
        if (acct.ok()) renderAccount(Json.map(acct.json()));
        else if (acct.status() == 404 || acct.status() == 422) { cashLbl.setText("no such account"); reservedLbl.setText("-"); availLbl.setText("-"); positions.getChildren().clear(); }
    }

    // =====================================================================
    // Rendering
    // =====================================================================
    private void renderMetrics(Map<String, Object> m) {
        if (m == null) return;
        mOrders.setText(INT.format(Json.num(m.get("ordersSubmitted"))));
        mTrades.setText(INT.format(Json.num(m.get("tradesExecuted"))));
        mShares.setText(INT.format(Json.num(m.get("executedVolume"))));
        mOps.setText(MONEY.format(Json.num(m.get("ordersPerSecond"))));
        double errs = Json.num(m.get("errorsCount"));
        mErrors.setText(INT.format(errs));
        mErrors.getStyleClass().remove("down");
        if (errs > 0) mErrors.getStyleClass().add("down");
        double a = Json.num(m.get("latencyP50Micros")), b = Json.num(m.get("latencyP95Micros")), c = Json.num(m.get("latencyP99Micros"));
        double max = Math.max(1, Math.max(a, Math.max(b, c)));
        p50.setText(lat(a)); p95.setText(lat(b)); p99.setText(lat(c));
        b50.setProgress(a / max); b95.setProgress(b / max); b99.setProgress(c / max);
    }

    private static String lat(double us) {
        if (us <= 0) return "-";
        if (us >= 1e6) return String.format("%.2f s", us / 1e6);
        if (us >= 1e3) return String.format("%.1f ms", us / 1e3);
        return Math.round(us) + " \u00b5s";
    }

    private void renderBook(Map<String, Object> m) {
        if (m == null) return;
        List<double[]> asks = levels(m.get("asks")), bids = levels(m.get("bids"));
        if (asks.size() > DEPTH) asks = asks.subList(0, DEPTH);
        if (bids.size() > DEPTH) bids = bids.subList(0, DEPTH);
        double[] at = cumulative(asks), bt = cumulative(bids);
        double max = Math.max(1, Math.max(at.length == 0 ? 0 : at[at.length - 1], bt.length == 0 ? 0 : bt[bt.length - 1]));

        ladder.getChildren().clear();
        ladder.getChildren().add(ladderRow("Price", "Quantity", "Total", "ladder-head", 0));
        if (asks.isEmpty() && bids.isEmpty()) {
            Label e = new Label("The book is empty. Start the simulator or place an order.");
            e.getStyleClass().add("muted");
            ladder.getChildren().add(e);
            return;
        }
        for (int i = asks.size() - 1; i >= 0; i--)
            ladder.getChildren().add(bookRow(asks.get(i), at[i], max, true));
        double spread = (!asks.isEmpty() && !bids.isEmpty()) ? asks.get(0)[0] - bids.get(0)[0] : Double.NaN;
        double mid = (!asks.isEmpty() && !bids.isEmpty()) ? (asks.get(0)[0] + bids.get(0)[0]) / 2 : Double.NaN;
        spreadLbl = new Label(Double.isNaN(spread) ? "One-sided book" : "Spread " + MONEY.format(spread) + "     Mid " + MONEY.format(mid));
        spreadLbl.getStyleClass().add("spread");
        spreadLbl.setMaxWidth(Double.MAX_VALUE);
        ladder.getChildren().add(spreadLbl);
        for (int i = 0; i < bids.size(); i++)
            ladder.getChildren().add(bookRow(bids.get(i), bt[i], max, false));
    }

    private Node bookRow(double[] lvl, double total, double max, boolean ask) {
        Region barR = new Region();
        barR.getStyleClass().add(ask ? "bar-ask" : "bar-bid");
        barR.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane sp = new StackPane(barR, ladderRow(MONEY.format(lvl[0]), INT.format(lvl[1]), INT.format(total), ask ? "ask" : "bid", 0));
        StackPane.setAlignment(barR, Pos.CENTER_RIGHT);
        barR.prefWidthProperty().bind(sp.widthProperty().multiply(total / max));
        barR.setMaxHeight(Double.MAX_VALUE);
        sp.getStyleClass().add("book-row");
        sp.setOnMouseClicked(e -> priceField.setText(String.format(Locale.ROOT, "%.2f", lvl[0])));
        return sp;
    }

    private GridPane ladderRow(String a, String b, String c, String cls, int ignored) {
        GridPane g = new GridPane();
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.3);
            g.getColumnConstraints().add(cc);
        }
        Label l1 = new Label(a), l2 = new Label(b), l3 = new Label(c);
        l1.getStyleClass().add(cls + "-price");
        for (Label l : List.of(l1, l2, l3)) { l.setMaxWidth(Double.MAX_VALUE); GridPane.setHgrow(l, Priority.ALWAYS); }
        l1.setAlignment(Pos.CENTER_LEFT); l2.setAlignment(Pos.CENTER_RIGHT); l3.setAlignment(Pos.CENTER_RIGHT);
        g.add(l1, 0, 0); g.add(l2, 1, 0); g.add(l3, 2, 0);
        g.setPadding(new Insets(3, 8, 3, 8));
        g.getStyleClass().add(cls.equals("ladder-head") ? "ladder-head" : "ladder-row");
        return g;
    }

    private static List<double[]> levels(Object o) {
        List<double[]> out = new ArrayList<>();
        for (Object x : Json.list(o)) {
            Map<String, Object> l = Json.map(x);
            if (l != null) out.add(new double[]{Json.num(l.get("price")), Json.num(l.get("quantity"))});
        }
        return out;
    }
    private static double[] cumulative(List<double[]> lv) {
        double[] t = new double[lv.size()];
        double run = 0;
        for (int i = 0; i < t.length; i++) { run += lv.get(i)[1]; t[i] = run; }
        return t;
    }

    private void renderTrades(Map<String, Object> m) {
        if (m == null) return;
        record Raw(Instant at, double price, long qty, String buyer, String seller) {}
        List<Raw> raw = new ArrayList<>();
        for (Object o : Json.list(m.get("trades"))) {
            Map<String, Object> t = Json.map(o);
            if (t == null) continue;
            Instant at;
            try { at = Instant.parse(String.valueOf(t.get("executedAt"))); } catch (Exception e) { at = Instant.EPOCH; }
            raw.add(new Raw(at, Json.num(t.get("price")), (long) Json.num(t.get("quantity")),
                    String.valueOf((long) Json.num(t.get("buyUserId"))), String.valueOf((long) Json.num(t.get("sellUserId")))));
        }
        raw.sort(Comparator.comparing(Raw::at));
        List<Tr> rows = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            boolean up = i == 0 || r.price() >= raw.get(i - 1).price();
            rows.add(new Tr(r.at().atZone(ZoneId.systemDefault()).format(TIME), r.price(), r.qty(), r.buyer(), r.seller(), up));
        }
        Collections.reverse(rows); // newest first
        tape.getItems().setAll(rows);

        if (raw.isEmpty()) { lastPrice.setText("-"); change.setText("No trades yet for " + symbol); spark.getChildren().clear(); return; }
        double last = raw.get(raw.size() - 1).price(), first = raw.get(0).price(), d = last - first;
        lastPrice.setText(MONEY.format(last));
        change.setText(String.format("%s%s (%.2f%%) over the last %d trades", d >= 0 ? "+" : "", MONEY.format(d), first == 0 ? 0 : d / first * 100, raw.size()));
        change.getStyleClass().removeAll("up", "down");
        change.getStyleClass().add(d >= 0 ? "up" : "down");
        drawSpark(raw.stream().mapToDouble(Raw::price).toArray(), d >= 0);
    }

    private void drawSpark(double[] p, boolean up) {
        spark.getChildren().clear();
        if (p.length < 2) return;
        double min = Arrays.stream(p).min().getAsDouble(), max = Arrays.stream(p).max().getAsDouble();
        double w = spark.getPrefWidth(), h = spark.getPrefHeight(), rng = Math.max(1e-9, max - min);
        Polyline line = new Polyline();
        for (int i = 0; i < p.length; i++) {
            line.getPoints().add(i * w / (p.length - 1));
            line.getPoints().add(h - 4 - (p[i] - min) / rng * (h - 8));
        }
        line.getStyleClass().addAll("spark", up ? "spark-up" : "spark-down");
        spark.getChildren().add(line);
    }

    private void renderAccount(Map<String, Object> a) {
        if (a == null) return;
        cashLbl.setText(MONEY.format(Json.num(a.get("totalCash"))));
        reservedLbl.setText(MONEY.format(Json.num(a.get("reservedCash"))));
        availLbl.setText(MONEY.format(Json.num(a.get("availableCash"))));
        positions.getChildren().clear();
        Map<String, Object> pos = Json.map(a.get("positions"));
        if (pos == null || pos.isEmpty()) {
            Label l = new Label("No holdings");
            l.getStyleClass().add("muted");
            positions.getChildren().add(l);
            return;
        }
        pos.forEach((k, v) -> positions.getChildren().add(kv(k, boldLabel(INT.format(Json.num(v)) + " shares"))));
    }
    private Label boldLabel(String s) { Label l = new Label(s); l.getStyleClass().add("value"); return l; }

    // =====================================================================
    // Actions
    // =====================================================================
    private void updateSendButton() {
        sendBtn.setText(side.equals("BUY") ? "Send buy order" : "Send sell order");
        sendBtn.getStyleClass().removeAll("buy-btn", "sell-btn");
        sendBtn.getStyleClass().add(side.equals("BUY") ? "buy-btn" : "sell-btn");
    }

    private void updateNotional(String type) {
        try {
            long q = Long.parseLong(qtyField.getText().trim());
            if ("MARKET".equals(type)) { notional.setText("Market order: fills at best available prices"); return; }
            double p = Double.parseDouble(priceField.getText().trim());
            notional.setText("Order value: " + MONEY.format(p * q));
        } catch (Exception e) { notional.setText("Order value: -"); }
    }

    private void sendOrder(String type) {
        orderMsg.getStyleClass().removeAll("up", "down");
        long user, qty;
        try {
            user = Long.parseLong(userField.getText().trim());
            qty = Long.parseLong(qtyField.getText().trim());
            if (qty <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { orderMsg.setText("Trader ID and quantity must be positive whole numbers."); orderMsg.getStyleClass().add("down"); return; }
        String priceJson = "";
        if ("LIMIT".equals(type)) {
            try {
                double p = Double.parseDouble(priceField.getText().trim());
                if (p <= 0) throw new NumberFormatException();
                priceJson = ",\"price\":" + String.format(Locale.ROOT, "%.2f", p);
            } catch (NumberFormatException e) { orderMsg.setText("Enter a valid price for a limit order."); orderMsg.getStyleClass().add("down"); return; }
        }
        String body = "{\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"userId\":" + user + ",\"symbol\":\"" + symbol
                + "\",\"side\":\"" + side + "\",\"type\":\"" + type + "\",\"quantity\":" + qty + priceJson + "}";
        sendBtn.setDisable(true);
        orderMsg.setText("Sending...");
        String desc = side + " " + qty + " " + symbol + ("LIMIT".equals(type) ? " @ " + priceField.getText().trim() : " at market");
        call("POST", "/orders", body).whenComplete((r, err) -> Platform.runLater(() -> {
            sendBtn.setDisable(false);
            if (err != null) { orderMsg.setText("Request timed out. Check the book before sending again."); orderMsg.getStyleClass().add("down"); log("Order failed: " + err.getMessage()); return; }
            Map<String, Object> j = Json.map(r.json());
            if (r.ok() && j != null) {
                int n = Json.list(j.get("trades")).size();
                String msg = n > 0 ? "Filled: " + n + (n == 1 ? " trade" : " trades") + " executed." : "Accepted and resting in the book.";
                orderMsg.setText(msg); orderMsg.getStyleClass().add("up");
                log(desc + " -> " + msg);
            } else {
                String e = (j != null && j.get("error") != null) ? String.valueOf(j.get("error")) : (r.status() == 401 ? "API key missing or wrong." : "Rejected (HTTP " + r.status() + ").");
                orderMsg.setText(e); orderMsg.getStyleClass().add("down");
                log(desc + " -> rejected: " + e);
            }
            refresh();
        }));
    }

    private void showNotice(String msg) {
        boolean show = msg != null && !msg.isEmpty();
        notice.setText(show ? msg : "");
        notice.setVisible(show);
        notice.setManaged(show);
    }

    private void log(String msg) {
        if (logArea == null) return;
        logArea.appendText("[" + java.time.LocalTime.now().format(TIME) + "] " + msg + "\n");
        if (logArea.getLength() > 20000) logArea.deleteText(0, 5000);
    }

    @Override
    public void stop() { if (poller != null) poller.shutdownNow(); }

    public static void main(String[] args) { launch(args); }

    // =====================================================================
    // Minimal JSON reader (the API returns small, flat documents)
    // =====================================================================
    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            try { return text == null || text.isBlank() ? null : new Json(text).value(); } catch (Exception e) { return null; }
        }
        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
        @SuppressWarnings("unchecked")
        static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
        static double num(Object o) {
            if (o instanceof Number n) return n.doubleValue();
            try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = str(); ws(); i++; // ':'
                    m.put(k, value()); ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
        private String str() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(n);
                    }
                } else b.append(c);
            }
            i++;
            return b.toString();
        }
    }
}