package com.example.coupenapp.telegram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device Telegram listener. Long-polls the Telegram Bot API (getUpdates) on a
 * background thread, filters messages to the configured group, extracts a coupon
 * code, dedupes it, and hands it to a {@link CouponConsumer} on the main thread.
 *
 * <p>This class NEVER touches the redemption logic itself — it only calls the
 * consumer, which is expected to run the app's existing Apply + Fire.
 *
 * <p>Guarantees a single active listener via an {@link AtomicBoolean} + synchronized
 * start/stop. Reconnects with exponential backoff. TelegramBotManager is a process
 * singleton.
 */
public class TelegramBotManager {

    /** Receives a detected coupon on the MAIN thread. Returns true if it was claimed. */
    public interface CouponConsumer {
        boolean onCoupon(String code);
    }

    /** UI observer for state + log updates (called on the main thread). */
    public interface Listener {
        void onStateChanged(BotState state);
        void onLog(String line);
    }

    /** One-shot callback for the Test Connection button. */
    public interface TestCallback {
        void onResult(boolean ok, String message);
    }

    private static final String TAG = "TelegramBot";
    private static final String API = "https://api.telegram.org/bot";
    private static final int POLL_TIMEOUT_SEC = 30;
    private static final long CODE_TTL_MS = 5 * 60 * 1000; // ignore same code within 5 min
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final int MAX_LOG = 200;

    private static volatile TelegramBotManager instance;

    private final Context appContext;
    private final TelegramConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> logHistory = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private ExecutorService executor;
    private volatile HttpURLConnection activeConn;
    private volatile BotState state = BotState.STOPPED;
    private volatile CouponConsumer couponConsumer;

    // stats (updated on background thread, read on main — kept simple & volatile-ish via posts)
    private volatile String lastConnectionTime = "—";
    private volatile String lastCouponCode = "—";
    private volatile String lastCouponTime = "—";
    private volatile int totalReceived = 0;
    private volatile int totalProcessed = 0;
    private volatile String lastError = "—";

    // dedupe
    private long lastOffset;
    private String lastCode = null;
    private long lastCodeAt = 0;

    private TelegramBotManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.config = new TelegramConfig(appContext);
        this.lastOffset = config.getOffset();
        this.totalReceived = config.getTotalReceived();
        this.totalProcessed = config.getTotalProcessed();
    }

    public static TelegramBotManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (TelegramBotManager.class) {
                if (instance == null) instance = new TelegramBotManager(ctx);
            }
        }
        return instance;
    }

    // ── listeners / consumer ──────────────────────────────────────────────────
    public void addListener(Listener l)    { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public void setCouponConsumer(CouponConsumer c) { this.couponConsumer = c; }
    public void clearCouponConsumer(CouponConsumer c) { if (this.couponConsumer == c) this.couponConsumer = null; }

    // ── state / stats getters ───────────────────────────────────────────────────
    public BotState getState()          { return state; }
    public boolean  isRunning()         { return running.get(); }
    public String getLastConnectionTime() { return lastConnectionTime; }
    public String getLastCouponCode()   { return lastCouponCode; }
    public String getLastCouponTime()   { return lastCouponTime; }
    public int    getTotalReceived()    { return totalReceived; }
    public int    getTotalProcessed()   { return totalProcessed; }
    public String getLastError()        { return lastError; }
    public List<String> getLogHistory() { synchronized (logHistory) { return new ArrayList<>(logHistory); } }

    // ── control ─────────────────────────────────────────────────────────────────
    public synchronized void start() {
        if (running.get()) { log("Already running."); return; }
        String token = config.getBotToken();
        if (token == null || token.trim().isEmpty()) {
            setError("Bot token is empty — save it first.");
            setState(BotState.ERROR);
            return;
        }
        running.set(true);
        executor = Executors.newSingleThreadExecutor();
        executor.execute(this::pollLoop);
    }

    public synchronized void stop() {
        if (!running.get() && state == BotState.STOPPED) return;
        running.set(false);
        disconnectActive();
        if (executor != null) { executor.shutdownNow(); executor = null; }
        setState(BotState.STOPPED);
        log("Bot stopped.");
    }

    /** One-off getMe check for the Test Connection button. Safe to call while stopped. */
    public void testConnection(final TestCallback cb) {
        new Thread(() -> {
            try {
                String token = config.getBotToken();
                if (token == null || token.trim().isEmpty()) { postTest(cb, false, "Bot token is empty."); return; }
                JSONObject res = httpGetJson(API + token.trim() + "/getMe");
                if (res != null && res.optBoolean("ok", false)) {
                    String uname = res.getJSONObject("result").optString("username", "bot");
                    postTest(cb, true, "@" + uname);
                } else {
                    postTest(cb, false, res != null ? res.optString("description", "Invalid token") : "No response");
                }
            } catch (Exception e) {
                postTest(cb, false, e.getMessage());
            }
        }).start();
    }

    private void postTest(TestCallback cb, boolean ok, String msg) {
        if (cb != null) main.post(() -> cb.onResult(ok, msg));
    }

    // ── polling loop ──────────────────────────────────────────────────────────
    private void pollLoop() {
        setState(BotState.CONNECTING);
        final String token = config.getBotToken().trim();

        // Validate the token before entering the loop.
        try {
            JSONObject me = httpGetJson(API + token + "/getMe");
            if (me == null || !me.optBoolean("ok", false)) {
                setError(me != null ? me.optString("description", "Invalid bot token") : "Cannot reach Telegram");
                setState(BotState.ERROR);
                running.set(false);
                return;
            }
            lastConnectionTime = now();
            log("Connected as @" + me.getJSONObject("result").optString("username", "bot"));
        } catch (Exception e) {
            setError("Connection failed: " + e.getMessage());
            setState(BotState.ERROR);
            running.set(false);
            return;
        }

        setState(BotState.RUNNING);
        log("Listening for coupons in group: \"" + config.getGroupName() + "\"");

        long backoff = 1000;
        while (running.get()) {
            try {
                String url = API + token + "/getUpdates?timeout=" + POLL_TIMEOUT_SEC
                        + "&offset=" + (lastOffset + 1)
                        + "&allowed_updates=" + URLEncoder.encode("[\"message\",\"channel_post\"]", "UTF-8");
                JSONObject resp = httpGetJson(url);
                if (!running.get()) break;

                if (resp != null && resp.optBoolean("ok", false)) {
                    if (state != BotState.RUNNING) { setState(BotState.RUNNING); log("Reconnected."); }
                    backoff = 1000;
                    JSONArray updates = resp.optJSONArray("result");
                    if (updates != null) {
                        for (int i = 0; i < updates.length(); i++) {
                            handleUpdate(updates.getJSONObject(i));
                        }
                    }
                } else {
                    String desc = resp != null ? resp.optString("description", "getUpdates error") : "No response";
                    setError(desc);
                    setState(BotState.RECONNECTING);
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                }
            } catch (Exception e) {
                if (!running.get()) break;
                setError("Network: " + e.getMessage());
                setState(BotState.RECONNECTING);
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private void handleUpdate(JSONObject update) {
        long updateId = update.optLong("update_id", 0);
        if (updateId > lastOffset) {
            lastOffset = updateId;
            config.setOffset(lastOffset);
        }

        JSONObject msg = update.optJSONObject("message");
        if (msg == null) msg = update.optJSONObject("channel_post");
        if (msg == null) return;

        JSONObject chat = msg.optJSONObject("chat");
        if (chat == null) return;
        String title = chat.optString("title", "");
        String text = msg.optString("text", "");
        if (text.isEmpty()) return;

        // Only process messages from the configured group (by title, case-insensitive).
        String wanted = config.getGroupName();
        if (wanted != null && !wanted.trim().isEmpty()) {
            if (title.isEmpty()
                    || !title.toLowerCase(Locale.US).contains(wanted.trim().toLowerCase(Locale.US))) {
                return;
            }
        }

        String code = CouponExtractor.extract(text);
        if (code == null) return;

        // Code-level dedupe within a TTL window.
        long nowMs = System.currentTimeMillis();
        if (code.equals(lastCode) && (nowMs - lastCodeAt) < CODE_TTL_MS) {
            log("Duplicate ignored: " + code);
            return;
        }
        lastCode = code;
        lastCodeAt = nowMs;

        totalReceived++;
        config.setTotalReceived(totalReceived);
        lastCouponCode = code;
        lastCouponTime = now();
        log("📩 Coupon detected: " + code);

        deliver(code);
    }

    private void deliver(final String code) {
        main.post(() -> {
            boolean fired = false;
            CouponConsumer c = couponConsumer;
            if (c != null) {
                try {
                    fired = c.onCoupon(code);
                } catch (Exception e) {
                    setError("Deliver: " + e.getMessage());
                }
            }
            if (fired) {
                totalProcessed++;
                config.setTotalProcessed(totalProcessed);
                log("🚀 Claimed on all cards: " + code);
            } else {
                log("⚠ No active cards — open a site first. Not claimed: " + code);
            }
            notifyState();
        });
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────
    private JSONObject httpGetJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        activeConn = conn;
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout((POLL_TIMEOUT_SEC + 10) * 1000);
            int http = conn.getResponseCode();
            InputStream is = (http < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (sb.length() == 0) return null;
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
            if (activeConn == conn) activeConn = null;
        }
    }

    private void disconnectActive() {
        HttpURLConnection c = activeConn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private void setState(BotState s) {
        if (state == s) return;
        state = s;
        log("• " + s.label());
        notifyState();
    }

    private void setError(String e) {
        lastError = (e == null) ? "Unknown error" : e;
        Log.w(TAG, "Error: " + lastError);
    }

    private String now() { return clock.format(new Date()); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void log(String line) {
        final String entry = now() + "  " + line;
        synchronized (logHistory) {
            logHistory.add(entry);
            while (logHistory.size() > MAX_LOG) logHistory.remove(0);
        }
        main.post(() -> { for (Listener l : listeners) l.onLog(entry); });
    }

    private void notifyState() {
        final BotState s = state;
        main.post(() -> { for (Listener l : listeners) l.onStateChanged(s); });
    }
}
