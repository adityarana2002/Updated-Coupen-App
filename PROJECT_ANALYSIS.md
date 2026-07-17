# CoupenApp / CoupenAdmin — Full Technical Analysis

> **Prepared as a senior-developer code & architecture review.**
> Scope: the entire `d:\CoupenAppWork` workspace — the Android client (`Coupen-APP-main`) and the Spring Boot backend (`CoupenAdmin-main`).
> Date of review: **2026-07-17**.

---

## 1. Executive Summary

This workspace contains **two separate but related codebases** that together form a small commercial product:

| # | Project | Type | Stack | Role |
|---|---------|------|-------|------|
| 1 | `Coupen-APP-main` | Android app | Java + C++ (JNI/NDK), Gradle (Kotlin DSL) | The end-user client ("CoupenApp" / "Auto Gift Code Claimer") |
| 2 | `CoupenAdmin-main` | Web backend | Java 17, Spring Boot 4.0.3, Spring Security, JPA/MySQL, Thymeleaf | An admin panel + REST API to sell and manage **license keys** |

**What the product actually does:** the Android app opens *multiple parallel WebView sessions* pointing at a set of real-money gambling / "color-prediction" betting websites (calinw55, 5tgyh6, 6Club11, rajagames3, ranchi91, plus user-added URLs). It then injects a JavaScript payload (`content.js`) into every session to **automatically find the "gift code / redeem" field, fill it with a code, and click the redeem button across all sessions at once** ("Apply to All" / "Fire"). The app is gated behind a paid unlock (a secret key + expiry), and the Spring Boot backend is the licensing server intended to sell and control access to it.

**Headline findings:**

1. ✅ **Both projects are individually well-organized and, broadly, compile-able** — clean package structure, sensible layering on the backend, readable Java.
2. 🔴 **The app and the backend are NOT connected.** The backend is a complete license server, but the shipped app does **not** call it. The app still validates against a **hardcoded key (`"rana123"`) and a hardcoded expiry baked into C++**. The integration exists only as *documentation* (`solutionapp.md`), never wired into code.
3. 🔴 **The app is currently "expired" as of today.** The hardcoded expiry is **19 Feb 2026**; today is 17 Jul 2026, so on a real device the app would immediately show *"This app has expired"* and refuse to run.
4. 🟠 **The documentation is stale.** `flow.md` describes a *GeckoView + WebExtension* architecture that no longer exists — the app was migrated to native `WebView`, leaving orphaned code and layouts behind.
5. 🟠 **Security & correctness issues** exist on both sides (details in §7–§8).
6. ⚠️ **Legal/ethical concern:** the tool automates mass promo-code redemption on gambling sites and is sold via license keys. This is almost certainly a Terms-of-Service violation for the target sites and may be illegal depending on jurisdiction (see §9). This does not affect the *technical* review but must be flagged in any professional assessment.

---

## 2. Purpose of the Project (Plain English)

**CoupenApp** is an automation tool. A normal user who wanted to redeem a gift/coupon code on one of these betting sites would: open the site → log in → find the "redeem gift code" box → paste the code → click redeem. That's one account, one code, manually.

CoupenApp automates and **parallelizes** that:

- The user picks a target site and a number of "pages" (1–10 simultaneous WebViews, each effectively a separate logged-in session/account).
- The user pastes one gift code.
- **"Apply to All"** fills the code into every open session.
- **"Fire"** fills *and* clicks redeem in every session simultaneously.

So the value proposition is *speed and scale* — claiming time-limited promo codes across many accounts faster than any human could by hand. **CoupenAdmin** is the monetization/control layer: an operator generates 12-character license keys, binds each to one device, sets an expiry, and can deactivate keys remotely. The app is sold (via a Telegram contact, `t.me/Ritik_jangid`) and unlocked with these keys.

---

## 3. System Architecture

### 3.1 Intended architecture (per `solutionapp.md`)

```
┌─────────────────┐        ┌──────────────────────┐        ┌──────────┐
│   CoupenApp     │  REST  │   CoupenAdmin        │  JPA   │  MySQL   │
│   (Android)     │ ─────► │   (Spring Boot)      │ ─────► │ (Aiven)  │
│  WebView + JNI  │  JSON  │   /api/login etc.    │        │ licenses │
└─────────────────┘        └──────────────────────┘        └──────────┘
        │
        ▼ injects content.js into
┌──────────────────────────────┐
│  Target gambling websites     │
│  (calinw55, 6club11, …)       │
└──────────────────────────────┘
```

### 3.2 Actual, as-built architecture

```
┌─────────────────────────────┐            ┌──────────────────────┐
│   CoupenApp (Android)        │            │   CoupenAdmin        │
│  ┌────────────────────────┐  │    ✗ no    │   (Spring Boot)      │
│  │ StartupCheckActivity   │  │  network   │   Fully functional,  │
│  │  key == native "rana123"│ │   link     │   but UNUSED by app  │
│  │  expiry == C++ {2026,1,19}│ │◄╌╌╌╌╌╌╌╌╌►│                      │
│  └────────────────────────┘  │            └──────────────────────┘
│  ┌────────────────────────┐  │
│  │ MainActivity            │  │  injects   ┌──────────────────────┐
│  │  N × WebView + content.js│─┼──────────► │ Gambling websites     │
│  └────────────────────────┘  │            └──────────────────────┘
└─────────────────────────────┘
```

**The dashed line is the gap.** The two halves were designed to meet at the REST API but never do in the current code.

---

## 4. Component 1 — `Coupen-APP-main` (Android Client)

### 4.1 Build configuration
- `app/build.gradle.kts`: `applicationId = com.example.coupenapp`, `compileSdk = 36`, `minSdk = 26`, `targetSdk = 34`, `versionName = "1.2"`, `versionCode = 3`.
- Native build via CMake (`externalNativeBuild`), C++ source in `src/main/cpp/native-lib.cpp`.
- Release build enables R8 minify + resource shrink; ProGuard rules default.
- Java 11 source/target compatibility.
- ⚠️ `compileSdk = 36` with `targetSdk = 34` — mixed; not fatal, but target should generally track a released API level and be raised deliberately.

### 4.2 Source files
| File | Responsibility |
|------|----------------|
| `StartupCheckActivity.java` | Launcher screen. Key-gate + expiry-gate ("license" check). |
| `MainActivity.java` | The real app: multi-WebView orchestration, gift-code automation, custom-URL management, persistence. |
| `ManageUrlActivity.java` | Tiny form returning a `{name, url}` result for a new custom link. |
| `LoginStorage.java` | **Dead code.** A SharedPreferences-backed credential store; a leftover "plain-Java replacement for the old GeckoView Autocomplete.StorageDelegate". Not referenced anywhere. |
| `cpp/native-lib.cpp` | JNI functions returning the hardcoded secret key + expiry date. |
| `assets/content.js` | The injected automation payload (v6). |

### 4.3 Startup / licensing flow (`StartupCheckActivity`)
1. `static { System.loadLibrary("coupenapp"); }` loads the native `.so`.
2. `onCreate` → `isAppExpired()`:
   - `isTampered()` — returns true if `System.currentTimeMillis() < lastRun` (a stored timestamp), i.e. a naive **clock-rollback** check.
   - `getExpiryDate()` (native) returns `{2026, 1, 19}` where **month is 0-indexed**, so this is **19 February 2026**.
   - Compares against `Calendar` (also 0-indexed month) → consistent, but the date is in the past.
3. If not expired, it shows a text field. The entered key is compared to `getSecretKey()` (native → `"rana123"`). On match it stores "now" and launches `MainActivity`.

**The "security" here is cosmetic:**
- The secret key `"rana123"` is a plaintext string literal compiled into `libcoupenapp.so`; it is trivially recoverable with `strings` on the extracted library.
- The expiry is likewise a constant in the binary.
- Because the check is fully offline and local, it can be bypassed by patching the library or the APK — there is no server-side enforcement (even though a server exists).

### 4.4 Main automation engine (`MainActivity`)
- Maintains a `List<Link>` (5 hardcoded default sites + user-added custom links persisted as JSON in SharedPreferences).
- `handleLinkClick` → `setupWebViewSessions(numPages, link)`:
  - Destroys old WebViews, creates `numPages` new `WebView`s (each in a styled "card"), and staggers their loading 150 ms apart.
  - Each WebView: JavaScript enabled, DOM storage on, third-party cookies on, and on `onPageFinished` it injects `content.js`.
- `applyGiftCodeToAll` / `fireGiftCodeToAll`: dispatch a JS `CustomEvent('CoupenCommand', {detail:{gc, fire}})` into every WebView via `evaluateJavascript`. The gift code has single quotes escaped before interpolation.
- Persistence: `saveAppState`/`restoreAppState` (last page count), `saveLinks`/`loadLinks` (custom links).

### 4.5 The injected payload (`content.js`)
- Guards against double-injection (`window.__coupenAppInjected`).
- Listens for the `CoupenCommand` event (and also URL hash/query params) and starts a `requestAnimationFrame` polling loop (up to 300 attempts) looking for the gift-code input.
- `findGiftCodeInput()` uses a prioritized selector list plus heuristics (placeholder/name/id contains "gift"/"code"/"redeem"), falling back to "the only visible text input".
- `applyCode()` sets the value via the native `HTMLInputElement` value setter and dispatches `input`/`change` events (correct technique for React/Vue-controlled inputs), then highlights the field green.
- `clickRedeemButton()` scans buttons/links for text matching `receive|redeem|claim|confirm|apply|submit`, force-enables them, and clicks.

This is a competent, defensively-written content script — the most technically solid part of the app.

---

## 5. Component 2 — `CoupenAdmin-main` (Spring Boot Backend)

### 5.1 Layering (clean, conventional)
```
controller ──► service ──► repository ──► entity ──► MySQL
   │
   └─► Thymeleaf templates (dashboard, login, license-form, user-login)
config: SecurityConfig (Spring Security)
```

| Layer | Class | Notes |
|-------|-------|-------|
| Entity | `License` | `id, licenseKey(unique,20), androidId(64), userName(100), expiryDate, isActive, createdAt`. Lombok `@Data`, `@PrePersist` sets `createdAt`. |
| Repository | `LicenseRepository` | `JpaRepository` + `findByLicenseKey`, `findByAndroidId`. |
| Service | `LicenseService` | Key generation (`SecureRandom`, 12-char A–Z0–9, collision-checked), `validateLicense`, `checkValidity`, CRUD. |
| Controller (web) | `AdminController` | `/admin/dashboard`, license new/save/edit/toggle/delete. Computes stats in Java. |
| Controller (API) | `ApiController` | `POST /api/login`, `GET /api/validate/{key}`. |
| Controller (pages) | `LoginController` | Serves `/login` and `/user/login` templates. |
| Config | `SecurityConfig` | In-memory admin user from env vars, BCrypt, form login, `/api/**` + `/user/**` public. |

### 5.2 Licensing logic (`LicenseService`)
- `validateLicense(key, androidId, userName)` — the "real" activation:
  - Returns structured statuses: `INVALID_KEY`, `DEACTIVATED`, `EXPIRED`, `DEVICE_MISMATCH`, `VALID`.
  - **Device binding**: on first successful validation with an empty `androidId`, it binds the calling device's ID to the key. Subsequent calls from a different device → `DEVICE_MISMATCH`. This is a sensible one-device-per-key model.
  - Returns `daysRemaining` via `ChronoUnit.DAYS.between`.
- `checkValidity(key)` — lightweight startup re-check; existence + active + expiry, no device check.

### 5.3 Configuration & deployment
- `application.properties` is **fully externalized** to environment variables (`PORT`, DB URL/user/password, `ADMIN_USERNAME`, `ADMIN_PASSWORD`). ✅ Good — no secrets in the repo.
- `spring.jpa.hibernate.ddl-auto=update` — convenient, but risky for production schema management.
- MySQL dialect; `solutionapp.md` references an Aiven-hosted MySQL.
- `Dockerfile` + Maven wrapper present for containerized deploy.

### 5.4 Admin UI
- Thymeleaf dashboard with stat cards (total/active/inactive/expired), a license table, and generate/edit/toggle/delete actions. Includes decorative animated CSS. Functional and reasonably polished.

---

## 6. The Critical Gap — Intended vs. Actual Integration

`solutionapp.md` is a **complete, correct integration guide**: it provides a ready-to-use `LicenseManager.java` (using `HttpURLConnection` on a background `ExecutorService`, `Settings.Secure.ANDROID_ID`, local caching, offline fallback) and shows exactly how `MainActivity` should call `POST /api/login` and `GET /api/validate/{key}`.

**None of it is in the app.** Verified by inspection:
- No `LicenseManager.java` exists.
- `grep` for `HttpURLConnection`, `OkHttp`, `Retrofit`, `/api/login`, `licenseKey` in the app source → **no matches**.
- The only "license" logic is the offline C++ key/expiry in `StartupCheckActivity`.

**Consequences:**
- The backend's device-binding, remote deactivation, and expiry management have **no effect** on the real app — they're unreachable.
- The four orphaned layouts (`activity_admin_login.xml`, `activity_admin_panel.xml`, `activity_app_lock.xml`, `activity_key_check.xml`) have **no backing Activity classes** and are never used — remnants of an earlier/abandoned licensing UI.

This is the single most important thing to understand about the current state: **the product is half-integrated.**

---

## 7. Is the Code Correct? — Correctness Analysis

**Short answer:** Each half is *internally* coherent and should compile, but there are real defects, and the app is **non-functional today** because of the expired hardcoded date. It is **not** production-correct as a whole.

### 7.1 Correctness / bug findings

| # | Severity | Location | Issue |
|---|----------|----------|-------|
| C1 | 🔴 Blocker (today) | `native-lib.cpp` / `StartupCheckActivity` | Hardcoded expiry is **19 Feb 2026**. As of 17 Jul 2026 the app self-expires on launch and cannot be used. |
| C2 | 🔴 Design defect | app ↔ backend | Advertised server-side licensing is **not implemented in the app** (see §6). The whole `CoupenAdmin` server is currently dead weight relative to the shipped client. |
| C3 | 🟠 Weak security | `StartupCheckActivity` / `native-lib.cpp` | Secret key & expiry are recoverable constants; check is offline and trivially bypassable. "Protection" is illusory. |
| C4 | 🟠 Bug | `MainActivity.loadLinks` + `addNewUrlButton` | Restored custom links get `buttonId = 0` (from `Link.fromJSON`), so every restored custom button is created with `setId(0)` → duplicate/zero view IDs. Works by luck because click handlers use closures, but it's a latent defect and breaks `findViewById` semantics. |
| C5 | 🟠 Bug | `MainActivity.destroyAllWebViews` | `WebView.destroy()` is called while the WebView may still be attached to its parent card; Android recommends removing from the view hierarchy *before* `destroy()`. Can cause exceptions/leaks on some devices. |
| C6 | 🟡 UX/logic mismatch | `activity_main.xml` vs `MainActivity.handleLinkClick` | Hint says *"How many IDs? (1-20)"* but code only accepts **1–10** ("Please enter a number between 1 and 10"). |
| C7 | 🟡 Weak anti-tamper | `StartupCheckActivity.isTampered` | Only detects clock rollback *below the last successful-run timestamp*, and `lastRun` is only written on successful key entry. Easily defeated (uninstall/clear data, or set clock forward then back). |
| C8 | 🟡 Dead code | `LoginStorage.java`, 4 orphan layouts, `slide_down.xml` | Unused artifacts from the GeckoView→WebView migration. Should be deleted to reduce confusion. |
| C9 | 🟡 Backend | `application.properties` | `ddl-auto=update` in production risks unintended schema changes; prefer Flyway/Liquibase + `validate`. |
| C10 | 🟡 Backend | `ApiController.validate` | License key travels in the **URL path** of a `GET` (`/api/validate/{key}`) → ends up in server/access logs and any intermediary. Prefer a header or POST body. |
| C11 | 🟡 Backend | `pom.xml` | Uses non-classic starter artifact names for Spring Boot 4.0.3 (`spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`). These differ from the Boot 3.x names (`-web`, `-test`). **Verify they resolve against the Boot 4.0.3 BOM before trusting the build** — I could not run Maven in this environment. |
| C12 | 🟢 Minor | `AdminController.saveLicense` | On create (`id == null`) it silently ignores the submitted `androidId`/`isActive`. Intentional-looking, but undocumented. |
| C13 | 🟢 Minor | `MainActivity` | No cleartext-traffic/mixed-content hardening; every target site loads with JS + third-party cookies enabled and `usesCleartextTraffic="true"`. Broad attack surface for a WebView that injects scripts. |

### 7.2 What *is* correct / well done
- `content.js` value-setter technique and event dispatch are the *right* way to drive framework-controlled inputs. ✅
- Gift code is escaped before JS interpolation (mitigates the most obvious injection). ✅
- Backend layering, DTO-less structured JSON responses, `SecureRandom` key generation with collision retry, and BCrypt for the admin password are all solid. ✅
- Secrets fully externalized to env vars. ✅
- Device-binding logic in `validateLicense` is logically sound. ✅

### 7.3 Build / compile verdict
- **Android:** The Java references (`R.id.*`, activities in the manifest, native method signatures matching the JNI names `Java_com_example_coupenapp_StartupCheckActivity_getSecretKey/…_getExpiryDate`) are consistent; it should compile and build an APK. It just self-expires at runtime today (C1).
- **Backend:** Structurally compiles cleanly; the **one thing to verify is the dependency coordinates** (C11) against the Spring Boot 4.0.3 BOM.

> Note: I reviewed the code statically. There is no live device/emulator or Maven/Gradle run in this environment, so "should compile" is a static judgment, not an executed build.

---

## 8. Security Assessment

| Area | Rating | Notes |
|------|--------|-------|
| Client-side license protection | 🔴 Poor | Offline, hardcoded, string-recoverable, patchable. Provides no real protection. |
| Backend auth (admin) | 🟢 Reasonable | Spring Security form login, BCrypt, single in-memory admin from env. Fine for a small tool; consider a real user store + MFA if it grows. |
| API exposure | 🟠 Mixed | `/api/**` is `permitAll` (needed for the app), but there's **no rate limiting** and the validate endpoint leaks the key via URL. Key space (36¹²) makes brute force impractical, but abuse/enumeration monitoring is absent. |
| Transport | 🟠 | `usesCleartextTraffic="true"` on the app; guide suggests HTTP for testing. Must be HTTPS end-to-end in production. |
| WebView hardening | 🟠 | JS + DOM storage + third-party cookies enabled on arbitrary user-added URLs, with script injection. A malicious custom URL could interact with injected globals. |
| CSRF | 🟢 | Disabled only for `/api/**` (correct for a stateless API); enabled for the admin web forms. |
| Secrets management | 🟢 | No secrets committed; all via env vars. |

---

## 9. Legal, Ethical & Risk Assessment

A professional review would be incomplete without this section.

- **Target sites** (calinw55, 5tgyh6, 6Club11, rajagames3, ranchi91) are real-money gambling / "color-prediction" platforms. In many jurisdictions — notably India, where the Telegram branding suggests the audience is — such platforms and/or the automation of them sit in a legally grey or prohibited area.
- **The tool's function** — running many parallel sessions to mass-claim promotional gift codes and force-click redeem — is designed to extract promotional value at a scale and speed the sites plainly do not intend. This is, at minimum, a **breach of those sites' Terms of Service** and could constitute **fraud/abuse** depending on how the codes and accounts are obtained.
- **It is sold as a product** (license keys, expiry, per-device binding, a paid Telegram contact), which increases exposure for whoever operates it.
- **Accounts/devices** using it risk bans and forfeiture; operators risk chargebacks, platform legal action, and jurisdiction-specific liability.

None of this changes the *engineering* conclusions above, but you asked for a senior-developer's honest read: **the technical work is competent, but it is in service of an activity that is high-risk legally and against the target platforms' rules.** Factor that into whether to invest further.

---

## 10. Documentation Drift

The repo's own docs partly describe a **different app than the one that exists**:

| Doc | Status |
|-----|--------|
| `flow.md` | 🔴 **Stale.** Describes GeckoView, `GeckoSession`, a `CoupenHelper` WebExtension, and URL-parameter injection. The real app uses native `WebView` + `evaluateJavascript` + `CustomEvent`. Rewrite or delete. |
| `solutionapp.md` | 🟡 **Accurate but aspirational.** Correct integration guide for code that was never added to the app. |
| `README.md` (app) | 🔴 **Broken.** UTF-16/garbled single line ("# Coupen-APP"). Effectively empty. |
| `brain.md`, `fix.md`, `analyze.md` | Developer scratch notes (not audited line-by-line here). |

---

## 11. Recommendations (Prioritized)

**If the goal is to make the current product work as designed:**
1. **(C1) Fix the runtime block first.** Either bump the hardcoded expiry, or — better — remove the offline gate entirely and adopt the server flow.
2. **(C2) Wire up the real licensing.** Drop in `LicenseManager.java` from `solutionapp.md`, add the license-input screen, and call `POST /api/login` / `GET /api/validate/{key}`. This is the intended design and makes the backend meaningful (remote deactivation, expiry control, device binding).
3. **(C11) Verify the backend build.** Run `./mvnw -q clean package` and confirm the Spring Boot 4.0.3 starter coordinates resolve; fall back to the standard `spring-boot-starter-web` / `spring-boot-starter-test` names if they don't.
4. **(C4, C5) Fix the WebView/link bugs.** Assign real generated IDs to restored custom links; remove WebViews from their parent before `destroy()`.
5. **(C6) Align the "pages" limit** — pick 10 or 20 and make hint + validation agree.

**Housekeeping / hardening:**
6. **(C8, §10)** Delete dead code (`LoginStorage.java`, 4 orphan layouts, unused anims) and rewrite `flow.md` + the broken `README.md` to match the actual WebView architecture.
7. **(C3, C7)** Stop pretending the offline key is security; rely on the server. If any client-side check remains, treat it as convenience only.
8. **(C9)** Replace `ddl-auto=update` with a migration tool (`validate` in prod).
9. **(C10, §8)** Move the license key out of the URL; add basic rate limiting and HTTPS enforcement.

**Before doing *any* of the above:** re-read §9 and decide whether continuing is acceptable given the legal/ToS exposure.

---

## 12. Final Verdict

| Dimension | Verdict |
|-----------|---------|
| Code organization & readability | 🟢 Good — clean packages, sensible layering, readable Java, a genuinely well-written content script. |
| "Is the code correct?" | 🟠 **Partially.** It should compile, but the app **self-expires today** (C1) and the advertised server integration **isn't implemented** (C2). Several real bugs (C4–C7). |
| Backend quality | 🟢 Solid for its size; verify Boot 4.0.3 dependency names (C11). |
| Client security model | 🔴 Ineffective (offline, hardcoded, patchable). |
| Documentation accuracy | 🟠 Drifted — `flow.md` describes an obsolete engine; README broken. |
| Legal / ethical | 🔴 High risk — gambling-site promo automation sold as a product (§9). |

**Bottom line:** This is *competently written code* that is **incomplete and currently non-functional** (expired), **secured only cosmetically**, and **documented for a design it doesn't yet implement** — all in service of a use case that carries significant legal and Terms-of-Service risk. The engineering fixes are straightforward and listed in §11; the bigger decision (§9) is whether to proceed at all.

---

*Analysis produced by static review of all source files in `Coupen-APP-main` and `CoupenAdmin-main`. No build was executed and no app was run in this environment; build/runtime claims are static judgments and are flagged as such.*
