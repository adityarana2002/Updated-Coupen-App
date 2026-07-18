package com.example.coupenapp;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.example.coupenapp.telegram.TelegramBotManager;
import com.example.coupenapp.telegram.TelegramConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private EditText giftCodeEditText;
    private Button applyCodeButton;
    private Button refreshAllButton;
    private Button manageUrlButton;
    private Button botControlButton;

    private LinearLayout webviewContainer;
    private LinearLayout buttonsContainer;
    private EditText numPagesEditText;
    private LinearLayout headerContainer;
    private TextView telegramHeaderLink;

    private final List<WebView> webViews = new ArrayList<>();
    private final ArrayList<Link> links = new ArrayList<>();

    private static final int MANAGE_URL_REQUEST_CODE = 1;
    private static final String PREFS_NAME = "CoupenAppPrefs";
    private static final String LINKS_KEY = "links";
    private static final String TAG = "MainActivity";
    // Prefix for the per-card isolated WebView profiles (one login per card).
    private static final String SESSION_PROFILE_PREFIX = "coupen_session_";
    // Card logins are kept for this long between app opens (sliding 24h window).
    private static final String SESSION_TIMESTAMP_KEY = "session_last_active";
    private static final long SESSION_LIFETIME_MS = 24L * 60 * 60 * 1000;

    private String currentLinkUrl;
    private String contentScript; // Cached content.js from assets
    private boolean sessionIsolationWarningShown = false;
    // Last code applied via "Apply to All" — re-applied to cards that finish loading later.
    private String pendingGiftCode = null;
    // Registered with TelegramBotManager so remote coupons reuse Apply + Fire.
    private TelegramBotManager.CouponConsumer couponConsumer;

    // ─── Link model ──────────────────────────────────────────────────────────

    private static class Link {
        final String name;
        final String url;
        int buttonId;
        Button button;
        boolean isCustom = false;

        Link(String name, String url, int buttonId) {
            this.name = name;
            this.url = url;
            this.buttonId = buttonId;
        }

        JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("name", name);
            json.put("url", url);
            json.put("isCustom", isCustom);
            return json;
        }

        static Link fromJSON(JSONObject json) throws JSONException {
            Link link = new Link(json.getString("name"), json.getString("url"), 0);
            link.isCustom = json.getBoolean("isCustom");
            return link;
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI references
        giftCodeEditText    = findViewById(R.id.giftCodeEditText);
        applyCodeButton     = findViewById(R.id.applyCodeButton);
        refreshAllButton    = findViewById(R.id.refreshAllButton);
        webviewContainer    = findViewById(R.id.webviewContainer);
        numPagesEditText    = findViewById(R.id.numPagesEditText);
        headerContainer     = findViewById(R.id.headerContainer);
        telegramHeaderLink  = findViewById(R.id.telegramHeaderLink);
        manageUrlButton     = findViewById(R.id.manageUrlButton);
        buttonsContainer    = findViewById(R.id.buttonsContainer);
        botControlButton    = findViewById(R.id.botControlButton);

        // Enable cookies globally for WebView
        CookieManager.getInstance().setAcceptCookie(true);

        // Load content script from assets once
        contentScript = loadAssetAsString("content.js");

        // Keep card logins for ~24h; clear them only if the app has been idle longer.
        enforceSessionLifetime();

        startHeaderAnimation();
        loadLinks();
        setupClickListeners();
        restoreAppState();

        // ── Telegram auto-claim: deliver detected coupons into the EXISTING Apply + Fire ──
        couponConsumer = this::onRemoteCoupon;
        TelegramBotManager botManager = TelegramBotManager.getInstance(this);
        botManager.setCouponConsumer(couponConsumer);
        if (new TelegramConfig(this).isAutoStart()) {
            botManager.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAppState();
        // Refresh the 24h window and persist cookies so logins survive app close.
        touchSessionTimestamp();
        flushSessionCookies();
    }

    @Override
    protected void onDestroy() {
        TelegramBotManager.getInstance(this).clearCouponConsumer(couponConsumer);
        destroyAllWebViews();
        super.onDestroy();
    }

    // ─── Asset loader ─────────────────────────────────────────────────────────

    private String loadAssetAsString(String filename) {
        try {
            InputStream is = getAssets().open(filename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load asset: " + filename, e);
            return "";
        }
    }

    // ─── Animations ──────────────────────────────────────────────────────────

    private void startHeaderAnimation() {
        Animation headerAnimation = AnimationUtils.loadAnimation(this, R.anim.header_animation);
        headerContainer.setVisibility(View.VISIBLE);
        headerContainer.startAnimation(headerAnimation);
    }

    // ─── Click listeners ──────────────────────────────────────────────────────

    private void setupClickListeners() {
        telegramHeaderLink.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Ritik_jangid"));
            startActivity(browserIntent);
        });

        for (Link link : links) {
            if (link.isCustom) {
                addNewUrlButton(link, false);
            } else {
                Button button = findViewById(link.buttonId);
                link.button = button;
                button.setBackgroundColor(getColorForLink(link.name));
                button.setTextColor(Color.WHITE);
                button.setOnClickListener(v -> handleLinkClick(link));
            }
        }

        applyCodeButton.setOnClickListener(v -> {
            String giftCode = giftCodeEditText.getText().toString().trim();
            if (!giftCode.isEmpty()) {
                if (webViews.isEmpty()) {
                    showToast("Please generate pages first.");
                    return;
                }
                applyAndFireToAll(giftCode);
            } else {
                showToast("Please enter a gift code.");
            }
        });

        refreshAllButton.setOnClickListener(v -> {
            if (webViews.isEmpty()) {
                showToast("Please generate pages first.");
                return;
            }
            for (WebView wv : webViews) {
                wv.reload();
            }
        });

        manageUrlButton.setOnClickListener(v -> {
            Animation hyperspaceJumpAnimation = AnimationUtils.loadAnimation(this, R.anim.hyperspace_jump);
            v.startAnimation(hyperspaceJumpAnimation);
            Intent intent = new Intent(MainActivity.this, ManageUrlActivity.class);
            startActivityForResult(intent, MANAGE_URL_REQUEST_CODE);
        });

        botControlButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, BotControlActivity.class)));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_URL_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra("name");
            String url  = data.getStringExtra("url");
            if (name != null && url != null && !name.isEmpty() && !url.isEmpty()) {
                Link newLink = new Link(name, url, View.generateViewId());
                newLink.isCustom = true;
                links.add(newLink);
                saveLinks();
                addNewUrlButton(newLink, true);
            }
        }
    }

    // ─── URL buttons ─────────────────────────────────────────────────────────

    private void addNewUrlButton(Link link, boolean isNew) {
        Button newButton = new Button(this);
        newButton.setText(link.name);
        newButton.setTextColor(Color.WHITE);
        newButton.setBackgroundColor(getColorForLink(link.name));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(16);
        newButton.setLayoutParams(params);

        buttonsContainer.addView(newButton);
        link.button = newButton;
        newButton.setId(link.buttonId);

        if (isNew) showToast("New URL added: " + link.name);

        newButton.setOnClickListener(v -> handleLinkClick(link));

        newButton.setOnLongClickListener(v -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Remove URL")
                    .setMessage("Are you sure you want to remove '" + link.name + "'?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        links.remove(link);
                        saveLinks();
                        buttonsContainer.removeView(newButton);
                        showToast("'" + link.name + "' removed.");
                    })
                    .setNegativeButton("No", null)
                    .show();
            return true;
        });
    }

    // ─── Session setup ────────────────────────────────────────────────────────

    private void handleLinkClick(Link link) {
        String numPagesStr = numPagesEditText.getText().toString();
        if (numPagesStr.isEmpty()) {
            showToast("Please enter the number of pages to generate.");
            return;
        }
        try {
            int numPages = Integer.parseInt(numPagesStr);
            if (numPages > 0 && numPages <= 20) {
                setupWebViewSessions(numPages, link);
            } else {
                showToast("Please enter a number between 1 and 20.");
            }
        } catch (NumberFormatException e) {
            showToast("Please enter a valid number.");
        }
    }

    private void setupWebViewSessions(int numPages, Link link) {
        // Destroy old WebViews properly
        destroyAllWebViews();
        currentLinkUrl = link.url;
        pendingGiftCode = null; // fresh run — nothing to auto-apply yet

        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenHeight = displayMetrics.heightPixels;
        int linkColor = getColorForLink(link.name);

        webviewContainer.removeAllViews();

        for (int i = 0; i < numPages; i++) {
            WebView webView = createWebView(i);
            webViews.add(webView);
            View cardView = createWebViewCard(i, webView, link.name, linkColor, screenHeight);
            webviewContainer.addView(cardView);
        }

        // Staggered loading: 150ms apart for stability
        loadWebViewUrlDelayed(0, link.url, 150);
    }

    /**
     * Creates a fully configured WebView with JS, DOM storage,
     * content script injection on page load, and basic ad/tracker blocking.
     */
    private WebView createWebView(int index) {
        WebView webView = new WebView(this);

        // ─── Per-card session isolation (a different login per card) ──────────
        // Standard Android WebViews all share ONE global cookie + storage jar,
        // so logging into an account on one card leaks into the others. We bind
        // each WebView to its own isolated Profile (separate cookies, localStorage
        // and cache), keyed by card position, so every card can hold a different
        // account. The profile MUST be attached before the WebView loads anything.
        CookieManager cookieManager;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            String profileName = SESSION_PROFILE_PREFIX + index;
            Profile profile = ProfileStore.getInstance().getOrCreateProfile(profileName);
            WebViewCompat.setProfile(webView, profileName);
            cookieManager = profile.getCookieManager();
        } else {
            // Old System WebView with no multi-profile support: fall back to the
            // shared session and warn the user once.
            cookieManager = CookieManager.getInstance();
            if (!sessionIsolationWarningShown) {
                showToast("Your Android System WebView is outdated — cards may share the same login. Please update it from the Play Store.");
                sessionIsolationWarningShown = true;
            }
        }
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject content script after every page load
                if (contentScript != null && !contentScript.isEmpty()) {
                    view.evaluateJavascript(contentScript, null);
                }
                // Auto-fill the current code on cards that finish loading later,
                // so "Apply to All" covers every card without re-clicking.
                if (pendingGiftCode != null && !pendingGiftCode.isEmpty()) {
                    view.evaluateJavascript(buildCoupenCommandJs(pendingGiftCode, false), null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Stay in-app for all URLs
                view.loadUrl(url);
                return true;
            }
        });

        return webView;
    }

    private View createWebViewCard(int position, WebView webView,
                                   String linkName, int linkColor, int screenHeight) {
        int cardHeight = (int) (screenHeight * 0.85);
        final float scale = getResources().getDisplayMetrics().density;

        LinearLayout userCard = new LinearLayout(this);
        userCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardHeight);
        cardParams.setMargins(0, 0, 0, (int) (32 * scale));
        userCard.setLayoutParams(cardParams);

        try {
            userCard.setBackground(ContextCompat.getDrawable(this, R.drawable.webview_border));
        } catch (Exception e) {
            userCard.setBackgroundColor(Color.LTGRAY);
        }
        userCard.setElevation(8 * scale);

        // Header bar
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setBackgroundColor(linkColor);

        TextView titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int pad = (int) (16 * scale);
        titleView.setPadding(pad, pad, pad, pad);
        titleView.setText(linkName + " - User " + (position + 1));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParams);

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> webView.reload());

        headerLayout.addView(titleView);
        headerLayout.addView(refreshButton);

        // WebView fills remaining space
        LinearLayout.LayoutParams wvParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        webView.setLayoutParams(wvParams);

        userCard.addView(headerLayout);
        userCard.addView(webView);

        return userCard;
    }

    private void loadWebViewUrlDelayed(int index, String url, long delayMs) {
        if (index >= webViews.size()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (index < webViews.size()) {
                webViews.get(index).loadUrl(url);
                loadWebViewUrlDelayed(index + 1, url, delayMs);
            }
        }, delayMs);
    }

    private void destroyAllWebViews() {
        for (WebView wv : webViews) {
            wv.stopLoading();
            wv.destroy();
        }
        webViews.clear();
    }

    // ─── Gift code automation ─────────────────────────────────────────────────

    /**
     * One tap: fills the gift code AND redeems it on every card at once. Built for
     * short redeem windows — the code and the redeem click are dispatched to all
     * cards in a single tight loop, and content.js applies + clicks in one pass.
     */
    private void applyAndFireToAll(String giftCode) {
        if (webViews.isEmpty()) {
            showToast("Please generate pages first.");
            return;
        }
        pendingGiftCode = giftCode; // remember so late-loading cards auto-fill too
        String js = buildCoupenCommandJs(giftCode, true); // fire = true → fill + redeem
        for (WebView wv : webViews) {
            wv.evaluateJavascript(js, null);
        }
        animateApplyButton();
    }

    /**
     * Builds the JS that dispatches a CoupenCommand into a page. The code is
     * escaped so a backslash or quote can't break the injected string.
     */
    private String buildCoupenCommandJs(String giftCode, boolean fire) {
        String safeCode = giftCode.replace("\\", "\\\\").replace("'", "\\'");
        return "window.dispatchEvent(new CustomEvent('CoupenCommand', "
                + "{ detail: { gc: '" + safeCode + "', fire: " + fire + " } }));";
    }

    private void animateApplyButton() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(applyCodeButton, "rotation", 0f, 360f);
        animator.setDuration(1000);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    // ─── Session lifetime (keep card logins for ~24h) ─────────────────────────

    /**
     * Keeps each card's login (cookies + local storage) for up to
     * {@link #SESSION_LIFETIME_MS}. If the app has been idle longer than that,
     * all saved sessions are cleared so a fresh login is required; otherwise the
     * logins are kept and the sites auto-sign-in when the cards reload. This runs
     * once on startup, before any card is opened.
     */
    private void enforceSessionLifetime() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastActive = prefs.getLong(SESSION_TIMESTAMP_KEY, 0);
        long now = System.currentTimeMillis();
        if (lastActive != 0 && now - lastActive > SESSION_LIFETIME_MS) {
            clearAllSessions();
        }
        touchSessionTimestamp(); // start / refresh the sliding 24h window
    }

    /** Records "now" as the last time the app was used. */
    private void touchSessionTimestamp() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(SESSION_TIMESTAMP_KEY, System.currentTimeMillis()).apply();
    }

    /** Persists cookies to disk so logins survive the app being closed or killed. */
    private void flushSessionCookies() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            CookieManager.getInstance().flush();
            return;
        }
        ProfileStore store = ProfileStore.getInstance();
        for (String name : store.getAllProfileNames()) {
            if (name.startsWith(SESSION_PROFILE_PREFIX)) {
                Profile p = store.getProfile(name);
                if (p != null) p.getCookieManager().flush();
            }
        }
    }

    /** Removes cookies + local storage from every per-card session (forces re-login). */
    private void clearAllSessions() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            return;
        }
        ProfileStore store = ProfileStore.getInstance();
        for (String name : store.getAllProfileNames()) {
            if (name.startsWith(SESSION_PROFILE_PREFIX)) {
                Profile p = store.getProfile(name);
                if (p != null) {
                    p.getCookieManager().removeAllCookies(null);
                    p.getCookieManager().flush();
                    p.getWebStorage().deleteAllData();
                }
            }
        }
    }

    // ─── Link color mapping ───────────────────────────────────────────────────

    private int getColorForLink(String linkName) {
        switch (linkName) {
            case "Calinw55":    return Color.parseColor("#1A237E"); // Dark Blue
            case "5tgyh6":      return Color.parseColor("#2E7D32"); // Dark Green
            case "6Club11":     return Color.parseColor("#D81B60"); // Pink
            case "rajagames3":  return Color.parseColor("#EF6C00"); // Dark Orange
            case "ranchi91":    return Color.parseColor("#4527A0"); // Deep Purple
            default:
                int hash = linkName.hashCode();
                return Color.rgb(
                        (hash & 0xFF0000) >> 16,
                        (hash & 0x00FF00) >> 8,
                        hash & 0x0000FF);
        }
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    private void saveAppState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String numPagesStr = numPagesEditText.getText().toString();
        if (!numPagesStr.isEmpty()) {
            editor.putString("saved_num_pages", numPagesStr);
        }
        editor.apply();
    }

    private void restoreAppState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedNumPages = prefs.getString("saved_num_pages", "");
        if (!savedNumPages.isEmpty()) {
            numPagesEditText.setText(savedNumPages);
        }
    }

    private void saveLinks() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        JSONArray jsonArray = new JSONArray();
        for (Link link : links) {
            if (link.isCustom) {
                try {
                    jsonArray.put(link.toJSON());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        editor.putString(LINKS_KEY, jsonArray.toString());
        editor.apply();
    }

    private void loadLinks() {
        links.clear();
        // Default links
        links.add(new Link("Calinw55",    "https://www.calinw55.com/#/main/RedeemGift",    R.id.button_calinw55));
        links.add(new Link("5tgyh6",      "https://www.5tgyh6.com/#/main/RedeemGift",      R.id.button_5tgyh6));
        links.add(new Link("6Club11",     "https://www.6club11.com/#/main/RedeemGift",      R.id.button_6club11));
        links.add(new Link("rajagames3",  "https://www.rajagames3.com/#/main/RedeemGift",   R.id.button_rajagames3));
        links.add(new Link("ranchi91",    "https://www.ranchi91.com/#/main/RedeemGift",     R.id.button_ranchi91));

        // Custom saved links
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String jsonString = prefs.getString(LINKS_KEY, null);
        if (jsonString != null) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                for (int i = 0; i < jsonArray.length(); i++) {
                    Link link = Link.fromJSON(jsonArray.getJSONObject(i));
                    if (link.isCustom) links.add(link);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ─── Telegram auto-claim hook ─────────────────────────────────────────────

    /**
     * Entry point for the Telegram bot — called on the main thread with a coupon
     * code detected in the group. Reuses the EXISTING Apply + Fire pipeline
     * unchanged (no WebView is recreated or reloaded).
     *
     * @return true if the coupon was fired into open cards, false if none are open.
     */
    public boolean onRemoteCoupon(String code) {
        if (code == null || code.trim().isEmpty()) return false;
        giftCodeEditText.setText(code);
        if (!webViews.isEmpty()) {
            applyAndFireToAll(code);
            return true;
        }
        showToast("Coupon received but no cards open: " + code);
        return false;
    }
}
