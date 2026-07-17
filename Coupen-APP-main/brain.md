# CoupenApp Brain Backup
**Date:** 2026-02-18
**Description:** Full code backup of the working state. This version uses DOM Event Messaging (`CustomEvent`) to apply coupons without page navigation or URL hash changes.

## 1. app/src/main/assets/messaging/content.js
**Logic:** Listens for `CoupenCommand` event on `window` and executes polling logic.
```javascript
console.log("CoupenApp: Content script loaded - v5");

// === INSTANT APPROACH: Read commands from URL Hash (no reload) ===
(function () {
    function processHash() {
        const hash = window.location.hash.substring(1); // Remove '#'
        if (!hash) return;

        const params = new URLSearchParams(hash);
        const giftCode = params.get('gc');
        const fireAction = params.get('fire');

        console.log("CoupenApp: Hash changed - gc=" + giftCode + " fire=" + fireAction);

        if (giftCode || fireAction === 'true') {
            // Start the optimized polling loop immediately
            startPolling(giftCode, fireAction);
        }
    }

    // Process on load (in case opened with hash)
    processHash();

    // Process on hash change (instant updates from app)
    window.addEventListener('hashchange', function () {
        console.log("CoupenApp: Hash event detected!");
        processHash();
    });

    // NEW: Listen for Custom DOM Events (bypasses URL completely)
    window.addEventListener('CoupenCommand', function (e) {
        console.log("CoupenApp: Command received via Event!", e.detail);
        if (e.detail) {
            startPolling(e.detail.gc, String(e.detail.fire));
        }
    });

    // Also check standard params for backward compatibility
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.has('gc') || urlParams.has('fire')) {
        startPolling(urlParams.get('gc'), urlParams.get('fire'));
    }
})();

function startPolling(giftCode, fireAction) {
    console.log("CoupenApp: Starting poll for " + giftCode);
    let attempts = 0;
    const maxAttempts = 300; // 15 seconds max (50ms * 300)
    let timer = null;

    function attempt() {
        attempts++;

        if (giftCode) {
            const input = findGiftCodeInput();
            if (input) {
                if (timer) cancelAnimationFrame(timer);
                applyCode(input, giftCode);

                // If fire too, click redeem IMMEDIATELY
                if (fireAction === 'true') {
                    clickRedeemButton();
                }
                return;
            }
        } else if (fireAction === 'true') {
            if (timer) cancelAnimationFrame(timer);
            clickRedeemButton();
            return;
        }

        if (attempts >= maxAttempts) {
            if (timer) cancelAnimationFrame(timer);
            console.log("CoupenApp: Timed out waiting for input after 15s");
        }

        // Loop as fast as possible using requestAnimationFrame
        timer = requestAnimationFrame(attempt);
    }

    // Start polling loop immediately
    attempt();
}

// --- Find the gift code input field ---
function findGiftCodeInput() {
    // Try specific selectors first
    var selectors = [
        'input[placeholder*="enter gift code" i]',
        'input[placeholder*="gift code" i]',
        'input[placeholder*="redeem" i]',
        'input[placeholder*="coupon" i]',
        'input#giftCode',
        'input[name*="gift" i]',
        'input[name*="code" i]',
        'input[name*="redeem" i]'
    ];

    for (var i = 0; i < selectors.length; i++) {
        var el = document.querySelector(selectors[i]);
        if (el && isVisible(el)) {
            console.log("CoupenApp: Found input via selector: " + selectors[i]);
            return el;
        }
    }

    // Fallback: scan all visible text inputs
    var inputs = document.querySelectorAll('input[type="text"], input:not([type])');
    for (var j = 0; j < inputs.length; j++) {
        var inp = inputs[j];
        if (!isVisible(inp)) continue;
        var ph = (inp.placeholder || '').toLowerCase();
        var nm = (inp.name || '').toLowerCase();
        var id = (inp.id || '').toLowerCase();
        if (ph.includes('gift') || ph.includes('code') || ph.includes('redeem') ||
            nm.includes('gift') || nm.includes('code') || id.includes('gift') || id.includes('code')) {
            console.log("CoupenApp: Found input via scan: placeholder='" + ph + "'");
            return inp;
        }
    }

    // Last resort: if only one visible text input exists, use it
    var visibleInputs = [];
    for (var k = 0; k < inputs.length; k++) {
        if (isVisible(inputs[k])) visibleInputs.push(inputs[k]);
    }
    if (visibleInputs.length === 1) {
        console.log("CoupenApp: Using the only visible text input");
        return visibleInputs[0];
    }

    return null;
}

// --- Apply the code to an input element ---
function applyCode(input, code) {
    input.focus();
    input.click();
    input.value = '';

    // React/Vue compatible value setting
    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    if (setter) {
        setter.call(input, code);
    } else {
        input.value = code;
    }

    // Fire all events frameworks listen for
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));

    // Visual feedback
    input.style.border = '3px solid #00FF00';
    input.style.backgroundColor = '#E0FFE0';

    console.log("CoupenApp: Applied gift code '" + code + "' successfully!");
}

// --- Click the redeem/receive button ---
function clickRedeemButton() {
    var buttons = document.querySelectorAll('button, a, input[type="button"], input[type="submit"], div[role="button"]');
    for (var i = 0; i < buttons.length; i++) {
        var btn = buttons[i];
        var text = (btn.innerText || btn.textContent || btn.value || '').trim().toLowerCase();

        if (/^(receive|redeem|claim|confirm|apply|submit)$/i.test(text)) {
            // Force enable button to bypass UI logic delays
            btn.disabled = false;
            btn.removeAttribute('disabled');
            btn.style.pointerEvents = 'auto';

            // Immediate click
            btn.click();
            return;
        }
    }
}

function isVisible(el) {
    if (!el) return false;
    return el.offsetWidth > 0 && el.offsetHeight > 0;
}
```

## 2. app/src/main/java/com/example/coupenapp/MainActivity.java
**Logic:** Manages Gecko sessions, dispatches JS events for coupons, handles page limit (10), and manages links.
```java
package com.example.coupenapp;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private EditText giftCodeEditText;
    private Button applyCodeButton;
    private Button fireButton;
    private Button refreshAllButton;
    private Button manageUrlButton;
    private RecyclerView recyclerView;
    private LinearLayout buttonsContainer;
    private EditText numPagesEditText;
    private LinearLayout headerContainer;
    private TextView telegramHeaderLink;

    private GeckoViewAdapter adapter;
    private final List<GeckoSession> sessions = new ArrayList<>();
    private final Map<GeckoSession, String> sessionUrls = new HashMap<>(); // Track current URL for each session
    private final ArrayList<Link> links = new ArrayList<>();

    private static final int MANAGE_URL_REQUEST_CODE = 1;
    private static final String PREFS_NAME = "CoupenAppPrefs";
    private static final String LINKS_KEY = "links";
    private static final String TAG = "MainActivity";

    private static GeckoRuntime sRuntime;
    private String currentLinkUrl; // Track current URL for apply/fire

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI
        giftCodeEditText = findViewById(R.id.giftCodeEditText);
        applyCodeButton = findViewById(R.id.applyCodeButton);
        fireButton = findViewById(R.id.fireButton);
        refreshAllButton = findViewById(R.id.refreshAllButton);
        recyclerView = findViewById(R.id.recyclerView);
        numPagesEditText = findViewById(R.id.numPagesEditText);
        headerContainer = findViewById(R.id.headerContainer);
        telegramHeaderLink = findViewById(R.id.telegramHeaderLink);
        manageUrlButton = findViewById(R.id.manageUrlButton);
        buttonsContainer = findViewById(R.id.buttonsContainer);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Initialize GeckoRuntime
        if (sRuntime == null) {
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .consoleOutput(true);
            sRuntime = GeckoRuntime.create(this, builder.build());
        }

        // Initialize LoginStorage
        LoginStorage loginStorage = new LoginStorage(this);

        // Set Autocomplete Storage Delegate
        sRuntime.setAutocompleteStorageDelegate(new Autocomplete.StorageDelegate() {
            @Nullable
            @Override
            public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(@NonNull String domain) {
                return GeckoResult.fromValue(loginStorage.getLogins(domain));
            }

            @Override
            public void onLoginSave(@NonNull Autocomplete.LoginEntry login) {
                loginStorage.saveLogin(login);
            }

            @Override
            public void onLoginUsed(@NonNull Autocomplete.LoginEntry login, int usedFields) {
                // Optional: update usage stats
            }
        });

        // Install WebExtension (for content script injection only)
        sRuntime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/messaging/", "coupenhelper@example.com")
                .accept(extension -> Log.d(TAG, "Extension installed: " + extension.id),
                        error -> Log.e(TAG, "Extension error", error));

        startHeaderAnimation();
        loadLinks();
        setupClickListeners();
        restoreAppState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void startHeaderAnimation() {
        Animation headerAnimation = AnimationUtils.loadAnimation(this, R.anim.header_animation);
        headerContainer.setVisibility(View.VISIBLE);
        headerContainer.startAnimation(headerAnimation);
    }

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
                if (sessions.isEmpty()) {
                    showToast("Please generate pages first.");
                    return;
                }
                applyGiftCodeToAll(giftCode);
            } else {
                showToast("Please enter a gift code.");
            }
        });

        fireButton.setOnClickListener(v -> {
            if (sessions.isEmpty()) {
                showToast("Please generate pages first.");
                return;
            }
            fireGiftCodeToAll();
        });

        refreshAllButton.setOnClickListener(v -> {
            if (sessions.isEmpty()) {
                showToast("Please generate pages first.");
                return;
            }
            for (GeckoSession session : sessions) {
                if (session.isOpen()) {
                    session.reload();
                }
            }
        });

        manageUrlButton.setOnClickListener(v -> {
            Animation hyperspaceJumpAnimation = AnimationUtils.loadAnimation(this, R.anim.hyperspace_jump);
            v.startAnimation(hyperspaceJumpAnimation);
            Intent intent = new Intent(MainActivity.this, ManageUrlActivity.class);
            startActivityForResult(intent, MANAGE_URL_REQUEST_CODE);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_URL_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra("name");
            String url = data.getStringExtra("url");
            if (name != null && url != null && !name.isEmpty() && !url.isEmpty()) {
                Link newLink = new Link(name, url, View.generateViewId());
                newLink.isCustom = true;
                links.add(newLink);
                saveLinks();
                addNewUrlButton(newLink, true);
            }
        }
    }

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

        if (isNew) {
            showToast("New URL added: " + link.name);
        }

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

    private void handleLinkClick(Link link) {
        String numPagesStr = numPagesEditText.getText().toString();
        if (numPagesStr.isEmpty()) {
            showToast("Please enter the number of pages to generate.");
            return;
        }
        try {
            int numPages = Integer.parseInt(numPagesStr);
            if (numPages > 0 && numPages <= 10) {
                setupGeckoSessions(numPages, link);
            } else {
                showToast("Please enter a number between 1 and 10.");
            }
        } catch (NumberFormatException e) {
            showToast("Please enter a valid number.");
        }
    }

    private void setupGeckoSessions(int numPages, Link link) {
        // Close existing sessions
        for (GeckoSession session : sessions) {
            session.close();
        }
        sessions.clear();
        currentLinkUrl = link.url; // Store for apply/fire

        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenHeight = displayMetrics.heightPixels;
        float density = displayMetrics.density;

        for (int i = 0; i < numPages; i++) {
            GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .contextId("session_" + i)
                    .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .build();

            GeckoSession session = new GeckoSession(settings);
            session.open(sRuntime);
            session.getSettings().setAllowJavascript(true);

            // Enable password saving prompt
            session.setPromptDelegate(new GeckoSession.PromptDelegate() {
                @Nullable
                @Override
                public GeckoResult<PromptResponse> onLoginSave(@NonNull GeckoSession session,
                        @NonNull GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption> request) {

                    // 1. Confirm the save
                    GeckoResult<PromptResponse> res = GeckoResult.fromValue(request.confirm(request.options[0]));

                    // 2. Schedule automatic redirect to the target URL
                    // Giving 3 seconds for the login to process and cookies to set
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (session.isOpen()) {
                            session.loadUri(link.url);
                        }
                    }, 3000);

                    return res;
                }
            });

            // Custom progress delegate to handle redirects if needed
            // For now, simple load. The extension handles interactions.
            session.loadUri(link.url);
            sessionUrls.put(session, link.url); // Initialize with start URL
            sessions.add(session);
        }

        int linkColor = getColorForLink(link.name);
        adapter = new GeckoViewAdapter(sessions, link.name, linkColor, screenHeight, density);
        recyclerView.setAdapter(adapter);

        // Fix black screen issue: Keep all views in memory
        recyclerView.setItemViewCacheSize(numPages);
    }

    // === SIMPLE URL-BASED APPROACH ===
    // Reload each page with gift code as URL parameter.
    // The content script reads it on page load. No messaging needed.

    private void applyGiftCodeToAll(String giftCode) {
        if (currentLinkUrl == null || sessions.isEmpty()) {
            showToast("Please generate pages first.");
            return;
        }

        String targetUrl = buildUrlWithParams(currentLinkUrl, Uri.encode(giftCode), false);
        Log.d(TAG, "Applying gift code via URL: " + targetUrl);

        String hashCommand = "gc=" + Uri.encode(giftCode);
        Log.d(TAG, "Applying gift code via Hash: " + hashCommand);

        for (GeckoSession session : sessions) {
            if (session.isOpen()) {
                // Use DOM Messaging - failsafe against navigation issues
                String js = "javascript:(function(){" +
                        "window.dispatchEvent(new CustomEvent('CoupenCommand', { detail: { gc: '" + giftCode
                        + "', fire: false } }));" +
                        "})()";
                session.loadUri(js);
            }
        }
    }

    private void fireGiftCodeToAll() {
        if (currentLinkUrl == null || sessions.isEmpty()) {
            showToast("Please generate pages first.");
            return;
        }

        // Include the gift code so it's applied before clicking redeem
        String giftCode = giftCodeEditText.getText().toString().trim();
        String encodedCode = !giftCode.isEmpty() ? Uri.encode(giftCode) : null;
        String targetUrl = buildUrlWithParams(currentLinkUrl, encodedCode, true);
        Log.d(TAG, "Firing via URL: " + targetUrl);

        String hashCommand = "gc=" + encodedCode + "&fire=true";
        Log.d(TAG, "Firing via Hash: " + hashCommand);

        for (GeckoSession session : sessions) {
            if (session.isOpen()) {
                // Use DOM Messaging
                String js = "javascript:(function(){" +
                        "window.dispatchEvent(new CustomEvent('CoupenCommand', { detail: { gc: '"
                        + (encodedCode != null ? giftCode : "") + "', fire: true } }));" +
                        "})()";
                session.loadUri(js);
            }
        }
    }

    private String updateUrlHash(String currentUrl, String newHash) {
        if (currentUrl == null)
            return null;
        String base = currentUrl.split("#")[0];
        return base + "#" + newHash + "&ts=" + System.currentTimeMillis();
    }

    private String buildUrlWithParams(String url, String encodedGiftCode, boolean fire) {
        int hashIndex = url.indexOf('#');
        String base = hashIndex >= 0 ? url.substring(0, hashIndex) : url;
        String hash = hashIndex >= 0 ? url.substring(hashIndex) : "";
        String sep = base.contains("?") ? "&" : "?";

        StringBuilder sb = new StringBuilder(base);
        if (encodedGiftCode != null) {
            sb.append(sep).append("gc=").append(encodedGiftCode);
            sep = "&";
        }
        if (fire) {
            sb.append(sep).append("fire=true");
        }
        sb.append(hash);
        return sb.toString();
    }

    private void animateFireButton() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(fireButton, "rotation", 0f, 360f);
        animator.setDuration(1000);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    private int getColorForLink(String linkName) {
        switch (linkName) {
            case "Calinw55":
                return Color.parseColor("#1A237E"); // Dark Blue
            case "5tgyh6":
                return Color.parseColor("#2E7D32"); // Dark Green
            case "6Club11":
                return Color.parseColor("#D81B60"); // Pink
            case "rajagames3":
                return Color.parseColor("#EF6C00"); // Dark Orange
            case "ranchi91":
                return Color.parseColor("#4527A0"); // Deep Purple
            default:
                int hash = linkName.hashCode();
                return Color.rgb(
                        (hash & 0xFF0000) >> 16,
                        (hash & 0x00FF00) >> 8,
                        hash & 0x0000FF);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onPause() {
        super.onPause();
        saveAppState();
    }

    private void saveAppState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        String numPagesStr = numPagesEditText.getText().toString();
        if (!numPagesStr.isEmpty()) {
            editor.putString("saved_num_pages", numPagesStr);
        }

        if (adapter != null) {
            editor.putString("saved_link_name", adapter.getLinkName());
        }

        editor.apply();
    }

    private void restoreAppState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedNumPages = prefs.getString("saved_num_pages", "");
        String savedLinkName = prefs.getString("saved_link_name", "");

        if (!savedNumPages.isEmpty() && !savedLinkName.isEmpty()) {
            numPagesEditText.setText(savedNumPages);

            // Find the link
            Link targetLink = null;
            for (Link link : links) {
                if (link.name.equals(savedLinkName)) {
                    targetLink = link;
                    break;
                }
            }

            if (targetLink != null) {
                try {
                    int numPages = Integer.parseInt(savedNumPages);
                    if (numPages > 0 && numPages <= 20) {
                        setupGeckoSessions(numPages, targetLink);
                        showToast("Restored previous session: " + targetLink.name);
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error restoring state", e);
                }
            }
        }
    }

    // Helper to get link name from adapter
    public String getCurrentLinkName() {
        return adapter != null ? adapter.getLinkName() : null;
    }

    private void loadLinks() {
        links.clear();
        // Add default links
        links.add(new Link("Calinw55", "https://www.calinw55.com/#/main/RedeemGift", R.id.button_calinw55));
        links.add(new Link("5tgyh6", "https://www.5tgyh6.com/#/main/RedeemGift", R.id.button_5tgyh6));
        links.add(new Link("6Club11", "https://www.6club11.com/#/main/RedeemGift", R.id.button_6club11));
        links.add(new Link("rajagames3", "https://www.rajagames3.com/#/main/RedeemGift", R.id.button_rajagames3));
        links.add(new Link("ranchi91", "https://www.ranchi91.com/#/main/RedeemGift", R.id.button_ranchi91));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String jsonString = prefs.getString(LINKS_KEY, null);
        if (jsonString != null) {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                for (int i = 0; i < jsonArray.length(); i++) {
                    Link link = Link.fromJSON(jsonArray.getJSONObject(i));
                    if (link.isCustom) {
                        links.add(link);
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}
```

## 3. app/src/main/java/com/example/coupenapp/GeckoViewAdapter.java
**Logic:** RecyclerView adapter for displaying GeckoSessions.
```java
package com.example.coupenapp;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

public class GeckoViewAdapter extends RecyclerView.Adapter<GeckoViewAdapter.ViewHolder> {

    private final List<GeckoSession> sessions;
    private final String linkName;
    private final int linkColor;
    private final int cardHeight;

    public GeckoViewAdapter(List<GeckoSession> sessions, String linkName, int linkColor, int screenHeight,
            float density) {
        this.sessions = sessions;
        this.linkName = linkName;
        this.linkColor = linkColor;
        this.cardHeight = (int) (screenHeight * 0.85);
    }

    public String getLinkName() {
        return linkName;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Programmatically create the item view structure to match previous logic
        // Structure:
        // LinearLayout (Card)
        // -> LinearLayout (Header)
        // -> TextView (Title)
        // -> Button (Refresh)
        // -> GeckoView

        android.content.Context context = parent.getContext();
        final float scale = context.getResources().getDisplayMetrics().density;

        LinearLayout userCard = new LinearLayout(context);
        userCard.setOrientation(LinearLayout.VERTICAL);
        // Height is fixed as per previous logic
        RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardHeight);
        cardParams.setMargins(0, 0, 0, (int) (32 * scale));
        userCard.setLayoutParams(cardParams);
        userCard.setBackground(ContextCompat.getDrawable(context, R.drawable.webview_border));
        userCard.setElevation(8 * scale);
        userCard.setClipToOutline(true);

        // Header
        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setPadding((int) (16 * scale), (int) (16 * scale), (int) (16 * scale), (int) (16 * scale));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParams);

        Button refreshButton = new Button(context);
        refreshButton.setText("Refresh");

        headerLayout.addView(titleView);
        headerLayout.addView(refreshButton);

        // GeckoView
        GeckoView geckoView = new GeckoView(context);
        LinearLayout.LayoutParams webViewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        geckoView.setLayoutParams(webViewParams);

        userCard.addView(headerLayout);
        userCard.addView(geckoView);

        return new ViewHolder(userCard, titleView, refreshButton, geckoView, headerLayout);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GeckoSession session = sessions.get(position);

        // Update Header
        holder.headerLayout.setBackgroundColor(linkColor);
        holder.titleView.setText(linkName + " - User " + (position + 1));

        // Setup Refresh Button
        holder.refreshButton.setOnClickListener(v -> {
            if (session.isOpen()) {
                session.reload();
            }
        });

        // Attach Session to GeckoView
        // Important: The session might be attached to another view if recycling is
        // buggy,
        // so releasing first is good practice, though onViewRecycled should handle it.
        holder.geckoView.releaseSession();
        holder.geckoView.setSession(session);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);

        // Crucial: Detach session when view is recycled to prevent memory leaks
        // and "Session already attached" errors.
        holder.geckoView.releaseSession();
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titleView;
        Button refreshButton;
        GeckoView geckoView;
        LinearLayout headerLayout;

        ViewHolder(View itemView, TextView titleView, Button refreshButton, GeckoView geckoView,
                LinearLayout headerLayout) {
            super(itemView);
            this.titleView = titleView;
            this.refreshButton = refreshButton;
            this.geckoView = geckoView;
            this.headerLayout = headerLayout;
        }
    }
}
```

## 4. app/build.gradle.kts
```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.coupenapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.coupenapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.geckoview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
```

## 5. app/src/main/AndroidManifest.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:usesCleartextTraffic="true"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.CoupenApp">
        <activity
            android:name=".StartupCheckActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name=".MainActivity"
            android:exported="false">
        </activity>
        <activity
            android:name=".ManageUrlActivity"
            android:exported="false">
        </activity>
    </application>

</manifest>
```
