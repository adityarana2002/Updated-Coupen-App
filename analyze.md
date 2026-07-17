# CoupenAppWork — Full Project Analysis

> **Date Analyzed:** 2026-03-03  
> **Workspace Root:** `d:\CoupenAppWork`

---

## 1. Workspace Overview

```
d:\CoupenAppWork
├── Coupen-APP-main/      ← Android Application (Java, GeckoView, NDK/C++)
└── CoupenAdmin-main/     ← Admin Web Backend (Spring Boot, Thymeleaf, MySQL)
```

The workspace contains **two separate but tightly coupled sub-projects** that together form a complete license-controlled coupon automation system:

| Project | Role | Language/Stack |
|---|---|---|
| `Coupen-APP-main` | Android client app for end users | Java, GeckoView, C++ NDK |
| `CoupenAdmin-main` | Admin web panel + REST API for license management | Spring Boot 4, Thymeleaf, MySQL |

---

## 2. Project 1 — `Coupen-APP-main` (Android App)

### 2.1 Build Configuration

| Property | Value |
|---|---|
| Build System | Gradle (Kotlin DSL) |
| Application ID | `com.example.coupenapp` |
| Namespace | `com.example.coupenapp` |
| CompileSDK | 36 |
| MinSDK | 26 (Android 8.0 Oreo) |
| TargetSDK | 34 (Android 14) |
| Version Code | 3 |
| Version Name | 1.2 |
| Native Build | CMake 3.22.1 (C++) |

**Key Dependencies:**
| Library | Purpose |
|---|---|
| `appcompat`, `material`, `activity`, `constraintlayout` | Standard AndroidX UI |
| `geckoview` | Mozilla GeckoView — embedded Firefox browser engine |
| `junit`, `espresso` | Testing |

---

### 2.2 App Architecture

The app has **3 Activities** (declared in `AndroidManifest.xml`):

```
App Launch
    └─► StartupCheckActivity  (LAUNCHER — main entry point)
            ├── App not expired → shows license key prompt
            │       └── Key OK → MainActivity
            └── App expired → shows dialog, force quits

MainActivity
    └─► ManageUrlActivity  (add custom URLs)
```

#### Permission
- `INTERNET` — required for GeckoView and API calls
- `android:usesCleartextTraffic="true"` — allows HTTP (not just HTTPS)

---

### 2.3 Source Files Analysis

#### `StartupCheckActivity.java` — App Entry & Security Guard

- **First security gate**: checks if the app has expired using a **hardcoded expiry date** from NDK native code.
- **Two-layer expiry check:**
  1. **Tamper detection** — uses `SharedPreferences` to save last run time; if current time < last run time (i.e., device clock was rolled back), it marks the app as tampered.
  2. **Date check** — calls native `getExpiryDate()` from C++ to get `{year, month, day}` and compares with `Calendar.getInstance()`.
- On expiry: shows `AlertDialog` that says "This app has expired. Please delete it and buy new APP" and calls `finish()`.
- **Second check**: prompts user to enter a **secret key** (also from NDK native `getSecretKey()`).
- Correct key → saves current timestamp → launches `MainActivity`.
- Has animations: `fade_in` and `slide_up` animations on UI components.
- **Telegram contact link**: `https://t.me/Ritik_jangid`

#### `native-lib.cpp` — NDK Secret Key & Expiry (C++)

```cpp
// Secret key hardcoded in native C++ (not visible in APK strings)
std::string secretKey = "rana123";

// Expiry date: {YYYY, MM, DD} — month is 0-indexed
jint dateElements[] = {2026, 1, 19}; // = February 19, 2026
```

> **Note:** The secret key `"rana123"` and expiry date `February 19, 2026` are **hardcoded in C++** for obfuscation — they won't appear in plain string extraction tools on the APK.

#### `MainActivity.java` — Core App Logic (691 lines)

This is the most complex file. Its responsibilities:

**A. URL / Website Management**
- Pre-loads 5 **default redeem URLs** (real gambling/gaming sites):
  | Name | URL |
  |---|---|
  | `Calinw55` | `https://www.calinw55.com/#/main/RedeemGift` |
  | `5tgyh6` | `https://www.5tgyh6.com/#/main/RedeemGift` |
  | `6Club11` | `https://www.6club11.com/#/main/RedeemGift` |
  | `rajagames3` | `https://www.rajagames3.com/#/main/RedeemGift` |
  | `ranchi91` | `https://www.ranchi91.com/#/main/RedeemGift` |
- Users can add **custom URLs** via `ManageUrlActivity`, persisted in `SharedPreferences` as JSON.

**B. GeckoView Session Management**
- Initializes a **singleton `GeckoRuntime`** with JavaScript enabled.
- On URL button click: creates 1–10 **isolated `GeckoSession` instances** (each in its own `contextId`), each rendered in a separate card.
- Sessions load staggered (150ms apart) to reduce memory spike.
- **Tracking protection** is enabled on each session.
- Each card has: a colored header bar, a session title, a per-session "Refresh" button, and a `GeckoView`.

**C. Gift Code Automation**
- User enters a gift code in `giftCodeEditText`.
- **"Apply Code"** button: dispatches a `CustomEvent('CoupenCommand', { gc: code, fire: false })` via `javascript:` URI to all open sessions.
- **"Fire"** button: dispatches same event with `fire: true` — triggers auto-click of the redeem button on the web page.
- **"Refresh All"** button: calls `session.reload()` on all sessions.

**D. Login Autocomplete**
- Integrates `GeckoView`'s `AutocompleteStorageDelegate` powered by `LoginStorage`.
- Saves/restores site credentials across sessions.

**E. WebExtension**
- Installs a built-in extension: `resource://android/assets/messaging/` with ID `coupenhelper@example.com`.
- This extension runs `content.js` on all web pages for automation.

**F. State Persistence**
- Saves `numPages` to `SharedPreferences` on `onPause()`.
- Restores state on `onCreate()`.

#### `LoginStorage.java` — Credential Persistence

- Stores `GeckoView` login credentials (username/password/origin) in `SharedPreferences` as a JSON array.
- Implements `Autocomplete.StorageDelegate` interface.
- Supports: `saveLogin()`, `getLogins(domain)` — filtered by origin domain.

#### `ManageUrlActivity.java` — Custom URL Manager

- Simple form with two fields: URL Name + URL.
- Returns name + URL via `Intent` back to `MainActivity`.
- No validation beyond empty-check.

#### `WebViewFragment.java` / `WebViewPagerAdapter.java`

- Helper classes (likely legacy or alternate session display approach — GeckoView cards replaced earlier ViewPager design).

---

### 2.4 WebExtension — `assets/messaging/`

The extension runs a **content script** (`content.js`) injected into every web page loaded by GeckoView.

**`content.js` Logic (185 lines):**
1. **Three trigger mechanisms** for receiving the gift code command:
   - URL Hash change: `#gc=CODE&fire=true`
   - URL Query params: `?gc=CODE&fire=true`
   - **Custom DOM Event**: `window.dispatchEvent(new CustomEvent('CoupenCommand', {detail:{gc,fire}}))`
2. **`startPolling(giftCode, fireAction)`**: uses `requestAnimationFrame` loop (max 300 tries ≈ 15 seconds) to poll for a gift code input field.
3. **`findGiftCodeInput()`**: searches by multiple CSS selectors (placeholder text like "gift code", "redeem", "coupon"; `id`, `name` attributes). Falls back to scanning all visible text inputs.
4. **`applyCode(input, code)`**: sets the value using the native `HTMLInputElement.value` setter (React/Vue compatible), dispatches `input` and `change` events, adds green border as visual feedback.
5. **`clickRedeemButton()`**: finds a button with text matching `receive|redeem|claim|confirm|apply|submit`, force-enables it (removes `disabled` attribute), and calls `.click()`.

**`manifest.json`:** Declares the extension with content script running on `<all_urls>`.  
**`background.js`:** Minimal background script (130 bytes), likely just for extension initialization.

---

### 2.5 Resources

- **Animations:** `fade_in.xml`, `slide_up.xml`, `button_click.xml`, `header_animation.xml`, `hyperspace_jump.xml`
- **Launcher Icon:** `ic_launcher-playstore.png` (84KB)
- **Drawables:** `webview_border` (card border for GeckoView sessions)
- **Layouts:**
  - `activity_startup_check.xml` — key entry screen with logo, input, Telegram link
  - `activity_main.xml` — main screen (URL buttons, numPages input, gift code input, action buttons, webviewContainer)
  - `activity_manage_url.xml` — URL add form

---

## 3. Project 2 — `CoupenAdmin-main` (Spring Boot Admin Panel)

### 3.1 Build Configuration

| Property | Value |
|---|---|
| Build System | Maven |
| Group ID | `com.admin` |
| Artifact ID | `CoupenAdmin` |
| Version | `0.0.1-SNAPSHOT` |
| Spring Boot | 4.0.3 |
| Java Version | 17 |
| Database | MySQL |
| Packaging | JAR (with Docker support) |

**Key Dependencies:**

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-jpa` | ORM / Hibernate |
| `spring-boot-starter-webmvc` | MVC + REST |
| `spring-boot-starter-thymeleaf` | Server-side HTML templates |
| `spring-boot-starter-security` | Authentication & Authorization |
| `thymeleaf-extras-springsecurity6` | Thymeleaf + Spring Security integration |
| `mysql-connector-j` | MySQL JDBC driver |
| `lombok` | Boilerplate code reduction |

---

### 3.2 Architecture

```
CoupenAdmin-main/
└── src/main/
    ├── java/com/admin/
    │   ├── CoupenAdminApplication.java     ← Spring Boot entry point
    │   ├── config/
    │   │   └── SecurityConfig.java         ← Spring Security + in-memory auth
    │   ├── controller/
    │   │   ├── AdminController.java        ← Web UI (Thymeleaf) CRUD
    │   │   ├── ApiController.java          ← REST API for Android app
    │   │   └── LoginController.java        ← Login/logout page routing
    │   ├── entity/
    │   │   └── License.java                ← JPA entity (licenses table)
    │   ├── repository/
    │   │   └── LicenseRepository.java      ← Spring Data JPA repository
    │   └── service/
    │       └── LicenseService.java         ← Business logic & key generation
    └── resources/
        ├── application.properties          ← Config (all env-vars)
        ├── static/                         ← CSS/JS assets
        └── templates/                      ← Thymeleaf HTML templates
            ├── login.html
            ├── dashboard.html
            ├── license-form.html
            └── user-login.html
```

---

### 3.3 Configuration (`application.properties`)

All sensitive config uses **environment variables** (12-factor app pattern):

```properties
server.port=${PORT}
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
admin.username=${ADMIN_USERNAME}
admin.password=${ADMIN_PASSWORD}
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.thymeleaf.cache=false
```

---

### 3.4 Data Model — `License` Entity

**Table:** `licenses`

| Column | Type | Constraints |
|---|---|---|
| `id` | BIGINT | PK, auto-increment |
| `license_key` | VARCHAR(20) | UNIQUE, NOT NULL |
| `android_id` | VARCHAR(64) | nullable |
| `user_name` | VARCHAR(100) | nullable |
| `expiry_date` | DATE | NOT NULL |
| `is_active` | BOOLEAN | NOT NULL, default `true` |
| `created_at` | DATETIME | NOT NULL, auto-set on insert |

---

### 3.5 Security Configuration (`SecurityConfig.java`)

- **In-memory single admin user** (username/password from env-vars, BCrypt hashed).
- **Route protection rules:**
  | Path | Access |
  |---|---|
  | `/api/**` | Public (CSRF disabled) |
  | `/user/**` | Public |
  | `/css/**`, `/js/**`, `/images/**` | Public |
  | `/login` | Public |
  | `/admin/**` | Authenticated (ROLE_ADMIN) |
- **Form login:** `/login` → success redirects to `/admin/dashboard`
- **Logout:** `/logout` → invalidates session, deletes `JSESSIONID` cookie

---

### 3.6 Controllers

#### `AdminController` — Web UI (Thymeleaf)

Routes under `/admin/**` (authenticated):

| Method | Route | Action |
|---|---|---|
| GET | `/admin/dashboard` | Show all licenses + stats (total, active, inactive, expired) |
| GET | `/admin/license/new` | Show blank license form |
| POST | `/admin/license/save` | Create or update a license |
| GET | `/admin/license/edit/{id}` | Show pre-filled edit form |
| GET | `/admin/license/toggle/{id}` | Toggle `isActive` flag |
| GET | `/admin/license/delete/{id}` | Delete a license |

#### `ApiController` — REST API for Android App

Routes under `/api/**` (public, no auth):

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/login` | Full license validation + device binding |
| GET | `/api/validate/{licenseKey}` | Quick startup validity check |

#### `LoginController`

- Handles `/login` (GET) to serve the Thymeleaf login page.
- Handles `/user/login` for user-facing login page (`user-login.html`).

---

### 3.7 `LicenseService` — Business Logic

**Key Generation:**
- Generates a **12-character uppercase alphanumeric** key using `SecureRandom`.
- Loops until a unique key is produced (collision-safe).
- Character set: `A-Z`, `0-9` (36 chars).

**`validateLicense(licenseKey, androidId, userName)`** — called by Android on login:

```
1. Key exists?          → NO  → INVALID_KEY
2. isActive?            → NO  → DEACTIVATED
3. expiryDate < today?  → YES → EXPIRED
4. androidId bound to different device? → DEVICE_MISMATCH
5. First login (no androidId)? → Bind androidId to license
6. → SUCCESS: return expiryDate, userName, daysRemaining
```

**`checkValidity(licenseKey)`** — called by Android on app startup:
- Same checks as above but without device binding (read-only).

---

### 3.8 Templates (Thymeleaf)

| File | Size | Purpose |
|---|---|---|
| `login.html` | 1.9 KB | Admin login page |
| `dashboard.html` | 6.0 KB | License management dashboard with stats |
| `license-form.html` | 3.6 KB | Create/edit license form |
| `user-login.html` | 21.7 KB | User-facing login page (larger — likely includes embedded UI) |

---

### 3.9 Docker Support

`Dockerfile` is present in `CoupenAdmin-main/`. The app is designed to be containerized and deployed (e.g., Railway, Render, VPS) with environment variables injected at runtime.

---

## 4. System Architecture — How Both Projects Connect

```
┌─────────────────────────────────────────────────────┐
│              Android App (Coupen-APP-main)           │
│                                                     │
│  [StartupCheckActivity] ──NDK──► secret key check   │
│         │ (license key + androidId)                 │
│         ▼  POST /api/login                          │
│  [MainActivity] ──HTTP──────────────────────────►   │
│         │  GET  /api/validate/{key}                 │
│         │                                           │
│  [GeckoView Sessions] ──content.js──► Gift Code     │
│         Automation on Gaming Websites               │
└─────────────────────────────────────────────────────┘
                    │ REST API (JSON)
                    ▼
┌─────────────────────────────────────────────────────┐
│         Spring Boot Server (CoupenAdmin-main)        │
│                                                     │
│  /api/login     ── LicenseService.validateLicense() │
│  /api/validate  ── LicenseService.checkValidity()   │
│                                                     │
│  /admin/**  ── AdminController (Thymeleaf UI)       │
│               Dashboard, CRUD, Toggle, Delete        │
│                                                     │
│  MySQL DB  ──── licenses table                      │
└─────────────────────────────────────────────────────┘
```

---

## 5. Security & Design Observations

| Observation | Details |
|---|---|
| **NDK Obfuscation** | Secret key and expiry date are in C++ code, not Java strings — makes reverse engineering slightly harder. |
| **Hardcoded secret key** | `"rana123"` in `native-lib.cpp` — changing requires recompile + APK rebuild. |
| **App expiry date** | February 19, 2026 (past at time of analysis — March 2026). App will show "App Expired" dialog. |
| **Cleartext traffic allowed** | `android:usesCleartextTraffic="true"` — HTTP calls to backend are allowed (not just HTTPS). |
| **In-memory admin auth** | `InMemoryUserDetailsManager` — single admin account; no DB-backed user table for admin. |
| **All config via env-vars** | Good 12-factor practice; no credentials hardcoded in `application.properties`. |
| **No JWT** | Admin web UI relies on traditional session cookies, not JWT. |
| **CSRF disabled for `/api/**`** | Necessary for mobile clients; correctly scoped. |
| **Device binding** | One license key = one device (androidId). Admin can reset by editing `android_id` field. |
| **License key collision safety** | `generateLicenseKey()` loops until unique — but no max-retries safeguard. |
| **`user-login.html` is 21KB** | Much larger than other templates — likely contains embedded scripts or a full SPA-style login flow. |
| **Commented-out RecyclerView code** | `MainActivity.java` has leftover commented imports/code from an older RecyclerView-based design. |
| **Deprecated `onActivityResult`** | Used in `MainActivity` for `ManageUrlActivity` result — should migrate to Activity Result API. |

---

## 6. File Count Summary

| Category | Count |
|---|---|
| Android Java source files | 6 |
| Spring Boot Java source files | 8 |
| Thymeleaf HTML templates | 4 |
| WebExtension JS files | 2 + 1 manifest |
| C++ native files | 2 (CMakeLists + native-lib.cpp) |
| Build/config files | `build.gradle.kts`, `pom.xml`, `application.properties`, `Dockerfile` |
| Documentation in project | `brain.md`, `fix.md`, `flow.md`, `solutionapp.md`, `README.md` |

---

## 7. Summary

**CoupenApp** is a **license-based coupon/gift code automation tool** consisting of:

1. **Android App** (`Coupen-APP-main`): A security-gated (NDK + expiry check) app that opens multiple GeckoView browser sessions on gaming/gambling websites. Using a bundled WebExtension (`content.js`), it automatically fills gift codes into redemption forms and clicks the claim button — simultaneously across all open sessions.

2. **Admin Backend** (`CoupenAdmin-main`): A Spring Boot web application with a protected dashboard where the admin manages license keys — creating, editing, toggling active/inactive, and deleting them. The backend also exposes a public REST API (`/api/login`, `/api/validate`) consumed by the Android app to validate license keys and bind them to specific Android devices.

**Primary use case:** A seller distributes license keys to customers. Customers enter the key in the Android app. The app verifies it online, then allows the customer to run automated gift code redemptions across multiple gaming site accounts simultaneously.
