# CoupenApp — Full Technical Analysis (Current State)

> **Senior-developer review of the project as it stands now**, after the recent refactor.
> Scope: `d:\CoupenAppWork` — which now contains a **single Android app** (`Coupen-APP-main`) plus docs.
> Last updated: **2026-07-17**.

---

## 1. Executive Summary

The workspace used to hold **two** projects (an Android app + a Spring Boot license backend). It has since been **reduced to a single Android app** and simplified/hardened. Current headlines:

| Aspect | Status |
|--------|--------|
| Backend (`CoupenAdmin`) | 🗑️ **Removed entirely** |
| Login / secret-key / expiry gate | 🗑️ **Removed** — app opens directly |
| Launcher | `MainActivity` (was `StartupCheckActivity`) |
| Native C++ / NDK | 🗑️ **Removed** (was only for the key/expiry) |
| Per-card login isolation | ✅ **Added** (`androidx.webkit` Profiles) |
| 24-hour session persistence | ✅ **Added** |
| One-tap **Apply + Fire** | ✅ **Added** (merged; old "Fire" button removed) |
| Auto-apply to late-loading cards + JS caching | ✅ **Added** |
| Telegram Bot auto-redeem | 📋 **Planned only** (see `BOT_CONTROL_PLAN.md`) — not implemented |
| Compiles / references resolve | ✅ Verified statically (no build executed here) |

**In one sentence:** the app is now a lean, single-module WebView automation tool that opens multiple isolated, persistent betting-site sessions and redeems a gift code across all of them with one tap — with the licensing/backend machinery stripped out.

---

## 2. Purpose of the Project

**CoupenApp** ("Auto Gift Code Claimer") automates and parallelizes gift-/coupon-code redemption on a set of real-money gambling / "color-prediction" betting sites (calinw55, 5tgyh6, 6Club11, rajagames3, ranchi91, plus user-added URLs).

Flow: the user opens **N parallel WebView cards** (each an isolated logged-in account) on a chosen site, pastes one gift code, and taps **Apply + Fire** — the app injects JavaScript (`content.js`) into every card to find the redeem field, fill the code, and click redeem, all at once. The value proposition is **speed and scale** — claiming time-limited promo codes across many accounts faster than a human could by hand.

---

## 3. What changed since the previous analysis

| # | Change | Effect |
|---|--------|--------|
| 1 | Deleted `CoupenAdmin-main` | No more Spring Boot server, DB, or REST licensing |
| 2 | Deleted `StartupCheckActivity` + `native-lib.cpp` + CMake | No key prompt, no hardcoded expiry; app was previously "expired", now it just opens |
| 3 | `MainActivity` promoted to launcher | Direct entry |
| 4 | Removed dead code | `LoginStorage.java`, 4 orphan login layouts, unused anims |
| 5 | Added `androidx.webkit:webkit:1.12.1` | Enables multi-profile session isolation |
| 6 | Per-card `Profile` isolation | Each card holds a **different** account (separate cookies/localStorage/cache) |
| 7 | 24h session persistence | Logins survive app close and are reused for ~24h |
| 8 | Auto-apply on `onPageFinished` + `content.js` element caching | Apply/Fire reaches all cards, including slow ones, with less latency |
| 9 | Merged **Apply + Fire** into one tap; removed the separate "Fire" button | Built for ~1-second redeem windows |

---

## 4. Current Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     CoupenApp (single Android module)         │
│                                                                │
│   MainActivity (launcher)                                      │
│    • header + site buttons + page count + gift-code input      │
│    • "Apply + Fire", "Refresh All", "Manage URL"               │
│    • builds N WebView cards, each on its own Profile            │
│    • 24h session lifetime management                           │
│         │ injects                                              │
│         ▼                                                      │
│   content.js  ── finds redeem field, fills code, clicks redeem │
│         │                                                      │
│   ManageUrlActivity — add a custom {name, url} link            │
└──────────────────────────────────────────────────────────────┘
          │ loads (HTTPS)                    │ per-card isolation
          ▼                                  ▼
   Betting / color-prediction sites   androidx.webkit Profiles
   (calinw55, 6club11, …)             (coupen_session_0..N)
```

There is **no backend and no network API of the app's own** — the only outbound traffic is the WebViews loading the target sites. (`INTERNET` permission is retained for that.)

---

## 5. Project Structure (current)

```
d:\CoupenAppWork\
├─ PROJECT_ANALYSIS.md        ← this file
├─ BOT_CONTROL_PLAN.md        ← design for the planned Telegram bot (not built)
├─ analyze.md                 ← older scratch notes
└─ Coupen-APP-main\
   ├─ app\
   │  ├─ build.gradle.kts     ← androidx.webkit added; no NDK/CMake
   │  └─ src\main\
   │     ├─ AndroidManifest.xml        ← MainActivity (launcher), ManageUrlActivity
   │     ├─ assets\content.js          ← injected automation script
   │     ├─ java\com\example\coupenapp\
   │     │  ├─ MainActivity.java        ← the whole app (658 lines)
   │     │  └─ ManageUrlActivity.java   ← add-URL form
   │     └─ res\
   │        ├─ layout\  activity_main.xml, activity_manage_url.xml
   │        └─ anim\    header_animation.xml, hyperspace_jump.xml
   ├─ gradle\libs.versions.toml    ← version catalog (+ webkit 1.12.1)
   └─ settings.gradle.kts, gradle.properties, local.properties, gradlew…
```

**Only two Java classes remain.** Everything the removed code referenced is gone; nothing dangles.

### Build/toolchain facts
- AGP **9.0.1**, Gradle **9.1.0**, `compileSdk 36`, `minSdk 26`, `targetSdk 34`, Java 11 source/target.
- Dependencies: appcompat, material, activity, constraintlayout, **androidx.webkit 1.12.1**.
- Requires **JDK 17+** (AGP 9 / Gradle 9) and the **API 36** SDK components; SDK is installed on this machine (`local.properties`).

---

## 6. Component Deep-Dive

### 6.1 `MainActivity.java` (the whole app)
Key responsibilities and where they live:

| Area | Methods |
|------|---------|
| Lifecycle | `onCreate` (direct entry, session-lifetime check), `onPause` (persist + flush cookies), `onDestroy` (destroy WebViews) |
| Links | `loadLinks` (5 defaults + custom from JSON), `saveLinks`, `addNewUrlButton`, `getColorForLink` |
| Sessions | `handleLinkClick` (1–20 pages), `setupWebViewSessions`, `createWebView(index)` (per-card Profile), `createWebViewCard`, `loadWebViewUrlDelayed` (150ms stagger), `destroyAllWebViews` |
| Redemption | `applyAndFireToAll` (one-tap fill+fire), `buildCoupenCommandJs`, `animateApplyButton` |
| 24h sessions | `enforceSessionLifetime`, `touchSessionTimestamp`, `flushSessionCookies`, `clearAllSessions` |
| Persistence | `saveAppState`/`restoreAppState` (page count), prefs file `CoupenAppPrefs` |

**Per-card isolation (`createWebView`)** — each WebView is bound, *before it loads*, to its own profile `coupen_session_<index>` via `WebViewCompat.setProfile`, guarded by `WebViewFeature.isFeatureSupported(MULTI_PROFILE)` with a graceful fallback + one-time warning on outdated System WebView.

**24h sessions** — `enforceSessionLifetime()` on startup clears all `coupen_session_*` profiles (cookies + `WebStorage`) if the app has been idle > `SESSION_LIFETIME_MS` (24h); otherwise it keeps them. `onPause` flushes cookies to disk so logins survive being killed. It's a **sliding window** (each app open refreshes the 24h).

**One-tap Apply + Fire** — `applyAndFireToAll(code)` sets `pendingGiftCode`, dispatches a `CoupenCommand` with `fire:true` into **every existing WebView** (no recreation), and animates the button. Late-loading cards get auto-filled via the `onPageFinished` hook (using `pendingGiftCode`).

### 6.2 `ManageUrlActivity.java`
A small form returning a `{name, url}` result; `MainActivity` turns it into a persistent custom link button (long-press to remove).

### 6.3 `content.js` (asset, labeled "v6")
Injected on every `onPageFinished` and driven via a `CoupenCommand` DOM event.
- `startPolling` — on the first synchronous pass, if the input exists it fills immediately; when firing, the redeem click happens **one animation frame** after the fill (so the site registers the value first).
- `findGiftCodeInput` / `clickRedeemButton` — prioritized selectors + heuristics, now with **element caching** (`cachedInput`, `cachedButton`) so repeated Apply/Fire are O(1).
- `applyCode` — uses the native value setter + dispatches `input`/`change` (correct for React/Vue inputs); `fireClick` force-enables the button before clicking.

---

## 7. Correctness Assessment (current)

### 7.1 Build / compile — ✅ should compile
- All `R.id`/`R.layout`/`R.anim`/`R.drawable` references exist; both manifest activities exist.
- All `libs.*` dependencies exist in the version catalog; `androidx.webkit` wired.
- No leftover references to any removed class, native method, layout, or animation (verified by search).
- All `androidx.webkit` APIs used (`ProfileStore`, `Profile.getCookieManager/getWebStorage`, `WebViewCompat.setProfile`, `WebViewFeature.MULTI_PROFILE`) exist in 1.12.1.

> Static verdict only — no Gradle build was executed in this environment. Verify with `./gradlew assembleDebug` (needs JDK 17+, API 36, internet on first run).

### 7.2 Behavior — ✅ works as designed
- Opens directly; multi-card sessions isolated; one-tap redeem to all cards; sessions persist ~24h.

### 7.3 Remaining minor issues (non-blocking)
| # | Severity | Location | Note |
|---|----------|----------|------|
| M1 | 🟡 | `loadLinks`/`addNewUrlButton` | Restored **custom** links get `buttonId = 0` (from `Link.fromJSON`), so those buttons are created with `setId(0)` — duplicate/zero IDs. Works only because click handlers use closures; still sloppy. |
| M2 | 🟡 | `setupWebViewSessions` → `destroyAllWebViews` | `WebView.destroy()` is called while views may still be attached (parent cleared just after). Prefer detach-then-destroy. |
| M3 | 🟢 | `content.js` line 1 | Header still says "v6" though it now has caching + one-frame fire — cosmetic version-label drift. |
| M4 | 🟢 | `createWebView` javadoc | Mentions "basic ad/tracker blocking" that doesn't exist — stale comment. |
| M5 | 🟢 | Session lifetime | Persistence ultimately depends on each **site's own token lifetime**; the app can't extend a server-side session beyond what the site allows. |

**Note:** the earlier "hint says 1–20 but code allows 1–10" mismatch is now **resolved** — the code accepts **1–20**, matching the hint.

---

## 8. Security Assessment (current)

| Area | Rating | Notes |
|------|--------|-------|
| Client licensing | ✅ N/A now | The hardcoded key/expiry (and its weak protection) were removed entirely. |
| Server attack surface | ✅ None | Backend deleted. |
| Session isolation | ✅ Good | Per-card Profiles keep accounts separate; cookies flushed to disk. |
| Transport | 🟠 | `usesCleartextTraffic="true"` remains (broad); target sites are HTTPS. Could be tightened. |
| WebView hardening | 🟠 | JS + third-party cookies enabled on arbitrary user-added URLs, with script injection — inherent to the app's function. |
| Stored data | 🟢 | Only links + page count + session timestamp in private prefs; no secrets. |

---

## 9. Planned (not yet built): Telegram Bot Control

A separate spec, **[BOT_CONTROL_PLAN.md](BOT_CONTROL_PLAN.md)**, designs an optional feature to auto-redeem coupons received via a Telegram bot (long-polling listener → extract code → call the existing `applyAndFireToAll`). It is **design-only**; no bot code exists in the app yet. It is intentionally additive and would not modify the redemption core.

---

## 10. Legal, Ethical & Risk Note

Unchanged from the prior review and still worth stating plainly: the app automates **mass promo-code redemption on real-money gambling sites**. This is, at minimum, a **breach of those sites' Terms of Service** and may be illegal depending on jurisdiction (the Telegram branding suggests an Indian audience, where such platforms are heavily restricted). Removing the license gate doesn't change this. The engineering here is competent; the **use case carries significant legal/ToS risk**, which you should weigh before investing further.

---

## 11. Final Verdict

| Dimension | Verdict |
|-----------|---------|
| Code organization | 🟢 Much cleaner now — one module, two classes, no dead code, no NDK. |
| "Is it correct?" | 🟢 Compiles (static check) and behaves as designed; only cosmetic/minor issues remain (§7.3). |
| Feature set | 🟢 Isolated per-card logins, 24h persistence, one-tap redeem — solid, purpose-built. |
| Security | 🟢 Improved (no fake licensing, no server); 🟠 WebView/cleartext are inherent trade-offs. |
| Legal / ethical | 🔴 High-risk use case (gambling-promo automation). |

**Bottom line:** the project is now a tidy, single-purpose Android app that does what it sets out to do, cleanly and quickly. The remaining engineering nits are minor. The dominant risk is not technical — it's the legal/ToS nature of the activity itself.

---

*Produced by static review of the current source in `Coupen-APP-main`. No build was run and no app was launched in this environment; build/behavior statements are static judgments and flagged as such.*
