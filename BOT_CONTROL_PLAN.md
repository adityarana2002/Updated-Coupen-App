# Telegram Bot Control — Feature Design & Implementation Plan

> **Status:** Proposal for your review. **No code has been written yet.**
> This document explains exactly what will be built, how it plugs into the existing app, and the decisions I need you to confirm before implementation.
> Prepared as a senior Android developer's technical spec.

---

## 1. Objective (in plain words)

Add a **Bot Control** panel to the app that runs a **Telegram listener** in the background. When a coupon message arrives in your Telegram chat, the app will **automatically** paste the code and run your existing **Apply + Fire** on every open WebView card — with **zero manual taps** after the bot is started.

Everything is **additive**. The existing coupon redemption (`applyAndFireToAll`, `content.js`, per-card profiles, 24h sessions) is **not modified or broken** — the bot simply *calls into* what you already have.

---

## 2. How it fits the existing app (the integration point)

Your app already has one method that does the whole job:

```
applyAndFireToAll(String giftCode)
   → sets pendingGiftCode
   → dispatches fill+redeem JS into EVERY existing WebView (no recreate, no refresh)
   → animates the Apply + Fire button
```

The Telegram bot's *only* job is to obtain a coupon code and call **that exact method** on the UI thread. That single fact is what makes this feature safe and non-breaking:

```
Telegram message ──► extract code ──► MainActivity.giftCodeEditText.setText(code)
                                  └──► applyAndFireToAll(code)   ← your existing logic
```

Requirement #8 ("reuse existing WebView sessions without recreating or refreshing") is satisfied automatically, because `applyAndFireToAll` dispatches JavaScript into the WebViews that are already open — it never rebuilds them.

---

## 3. High-level architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                          App process                                │
│                                                                     │
│   ┌─────────────────────────┐        ┌──────────────────────────┐   │
│   │   TelegramBotService     │        │      MainActivity        │   │
│   │  (foreground service)    │        │  (UI + WebView cards)    │   │
│   │  • keeps process alive   │        │  • Bot Control panel     │   │
│   │  • ongoing notification  │        │  • Status card + log     │   │
│   └───────────┬─────────────┘        │  • receives coupons ─────┼─┐ │
│               │ starts/stops          └──────────▲───────────────┘ │ │
│               ▼                                   │ callback         │ │
│   ┌─────────────────────────────────────────────┴───────────────┐  │ │
│   │              TelegramBotManager  (singleton)                  │  │ │
│   │  • one background thread (ExecutorService)                    │  │ │
│   │  • long-polling loop: getUpdates                              │  │ │
│   │  • state machine + stats + exponential backoff                │  │ │
│   │  • single-instance guarantee (AtomicBoolean)                  │  │ │
│   └───────────────────────────┬──────────────────────────────────┘  │ │
│                               │ HTTPS                                 │ │
└───────────────────────────────┼──────────────────────────────────────┘ │
                                ▼                                          │
                     ┌────────────────────┐      coupon (main thread) ────┘
                     │  Telegram Bot API   │
                     │ api.telegram.org    │
                     └────────────────────┘
```

**Two new runtime pieces:**
- **`TelegramBotManager`** — the brain. A singleton that owns the polling loop, state, stats, and reconnection. Independent of the UI.
- **`TelegramBotService`** — a foreground service whose only job is to keep the process alive while listening in the background and show the "🟢 Telegram Bot Running" notification.

The **UI** (a panel inside `MainActivity`) is just a thin observer of the manager's state and the place where received coupons are fired.

---

## 4. New components & responsibilities

| Component | Type | Responsibility |
|-----------|------|----------------|
| `TelegramBotManager` | Singleton (Java) | Start/stop the listener, run the `getUpdates` long-poll loop on a background thread, track offset, dedupe, extract coupon, hold state + stats, exponential-backoff reconnect, enforce single instance. Emits events to a listener interface. |
| `TelegramBotService` | Foreground `Service` | Keep the process alive; show/refresh the ongoing status notification; forward start/stop to the manager. Recommended for reliability (see §18, Decision A). |
| `BotState` | Enum | `STOPPED, CONNECTING, RUNNING, RECONNECTING, ERROR`. |
| `BotStats` | Small POJO | `lastConnectionTime, lastCouponCode, lastCouponTime, totalReceived, totalProcessed, lastError`. |
| `TelegramConfig` | Prefs helper | Load/save token, chat ID, auto-start flag, last update offset. |
| `CouponExtractor` | Utility | Filter by chat ID, validate + extract the code from message text, dedupe. |
| Bot Control UI | Layout + `MainActivity` code | The panel, status card, and live event log described in §5. |

**No third-party dependency is required.** The app already uses `HttpURLConnection` patterns and `org.json`; the Telegram API is plain HTTPS + JSON. (OkHttp is optional and not planned, to keep the build lean.)

---

## 5. UI design — the "Bot Control" panel

A new collapsible **Bot Control** card added to `MainActivity` (recommended) so the status and log are visible on the same screen as the WebView cards. Proposed layout:

```
┌─ Bot Control ──────────────────────────────────  [▾ collapse] ─┐
│                                                                 │
│  Bot Token   [ 123456789:ABCdef... ................ ] 👁        │
│  Chat ID     [ -1001234567890 ....................... ]         │
│                                                                 │
│  [ 💾 Save Configuration ]      [ 🔌 Test Connection ]          │
│                                                                 │
│  ┌───────────────────── Status ─────────────────────────────┐  │
│  │  🟢 Telegram Bot Running                                   │  │
│  │  Connection ....... Connected                             │  │
│  │  Last connected ... 2026-07-17 18:04:21                   │  │
│  │  Last coupon ...... "SAVE50"  @ 18:07:03                  │  │
│  │  Received ......... 12      Processed ....... 11          │  │
│  │  Last error ....... —                                     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  [ ▶ Start Bot ]                       [ ■ Stop Bot ]           │
│                                                                 │
│  ☐ Automatically Start Telegram Bot on App Launch               │
│                                                                 │
│  ┌──────────────── Live Event Log ─────────────────────────┐   │
│  │ 18:04:21  Connecting…                                    │   │
│  │ 18:04:22  ✅ Connected as @MyCouponBot                   │   │
│  │ 18:07:03  📩 Coupon received: SAVE50                     │   │
│  │ 18:07:03  🚀 Fired to 5 cards                            │   │
│  │ 18:09:10  ⚠ Network lost — reconnecting (backoff 2s)    │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

**Controls (exactly as requested):** Bot Token field, Chat ID field, Save Configuration, Test Connection, Start Bot, Stop Bot, Auto-Start toggle, real-time Status card, live Event Log.

**Status card fields:** Bot Status (Running / Stopped / Connecting / Reconnecting / Error), Telegram Connection Status, Last Connection Time, Last Coupon Received, Total Coupons Received, Total Coupons Processed, Last Error Message.

**Button behaviour:**
- **Start Bot** — validate token + chat ID → `getMe` connection check → start listener + foreground service → status turns 🟢. Disabled while running.
- **Stop Bot** — stop the loop, close the open HTTPS connection cleanly, stop the service, release the thread → status turns 🔴. Disabled while stopped.
- **Test Connection** — one-off `getMe` call; shows the bot's @username on success or the exact error on failure. Does **not** start listening.
- **Save Configuration** — persists token, chat ID, and the auto-start flag.

---

## 6. Bot state machine

```
                 Start Bot
   STOPPED ───────────────► CONNECTING
     ▲                          │  getMe OK
     │ Stop Bot                 ▼
     │                       RUNNING ──────────────┐
     │                          │                  │ network drops
     │                          │ fatal error      ▼
     │                          ▼              RECONNECTING
     └────────────────────── ERROR ◄──────────────┘  (retries fail
              (auth/config invalid)   backoff 1→2→4…→60s, then ERROR)
```

- **CONNECTING** — verifying token/chat before the first poll.
- **RUNNING** — actively long-polling, connection healthy.
- **RECONNECTING** — transient failure; retrying with exponential backoff. Auto-returns to RUNNING on success.
- **ERROR** — unrecoverable (invalid token, invalid chat, backoff ceiling hit). Requires user action; `lastError` is populated.

Every transition updates the Status card and appends to the Event Log.

---

## 7. Telegram integration details

Telegram bots receive messages two ways: **webhooks** (need a public server — not possible from a phone) or **long polling** (the app asks Telegram for new messages). We use **long polling** — the correct choice for a mobile app.

| Purpose | Endpoint | Notes |
|---------|----------|-------|
| Test / verify token | `GET /bot<token>/getMe` | Returns bot identity if the token is valid. |
| Listen for messages | `GET /bot<token>/getUpdates?offset=<n>&timeout=30&allowed_updates=["message"]` | Long-poll: the request blocks up to 30s until a message arrives. |
| (Optional) reply/ack | `POST /bot<token>/sendMessage` | Could confirm back to the chat "✅ SAVE50 fired to 5 cards" — see §18, Decision D. |

**Offset handling (dedupe at the source):** each update has an `update_id`. After processing, we poll with `offset = last_update_id + 1`, so Telegram never re-sends the same message. The offset is persisted so a restart doesn't reprocess old coupons.

**Timeouts:** connect ≈ 15s, read ≈ 40s (must exceed the 30s poll timeout). All on the background thread — the UI thread is never blocked.

---

## 8. Automatic coupon processing pipeline

When the bot is RUNNING and a message arrives, this runs end-to-end with no user interaction:

```
1. getUpdates returns an update
2. Is message.chat.id == saved Chat ID?            ── no ─► ignore (log "ignored: wrong chat")
3. Extract coupon code from message.text            ── fail ─► ignore (log "no code found")
4. Is code a duplicate of the last processed?       ── yes ─► ignore (log "duplicate")
5. totalReceived++, lastCoupon = code
   ── marshal to MAIN THREAD ──
6. giftCodeEditText.setText(code)                    (Requirement #4: update input field)
7. if (webViews not empty)  applyAndFireToAll(code)  (Requirements #5,#6,#7,#8: apply+fire to ALL cards, reuse sessions)
      else  log "no active cards — open a site first"
8. totalProcessed++
9. Append to Event Log with timestamp                (Requirement #9)
10. advance offset = update_id + 1
```

**Coupon extraction (`CouponExtractor`)** — this needs *your* input (see §18, Decision C). The default plan:
- Trim the message; if the whole message is a single code-like token, use it.
- Otherwise apply a **configurable regex** (default `\b[A-Za-z0-9]{4,20}\b`, first match) to pull the code out of a sentence like *"New code: SAVE50 🎉"*.
- Reject empty/too-short/invalid.
- **Dedupe:** ignore a code identical to the last one processed (and the `update_id` offset already prevents re-reading the same message).

**Safety note:** if a coupon arrives while **no WebView cards are open**, it cannot be fired (there's nothing to fire into). The bot will log this clearly rather than silently dropping it. Firing requires at least one active card — same as tapping Apply + Fire manually.

---

## 9. Reliability & threading model

| Requirement | How it's met |
|-------------|--------------|
| Never block the UI thread | All networking on a single-thread `ExecutorService`; UI/coupon delivery marshalled back via `Handler(Looper.getMainLooper())`. |
| Handle temporary network failures | Failures caught per-poll; loop continues, state → RECONNECTING. |
| Automatic reconnect | Loop retries automatically after any recoverable error. |
| Exponential backoff | Delay `1s → 2s → 4s → 8s → … → 60s` cap; reset to 1s on a successful poll. |
| Only one listener ever | `AtomicBoolean running` + `synchronized` start/stop; a second Start is a no-op while active. The manager is a process-wide singleton. |
| Clean shutdown | Stop flips `running=false`, interrupts the thread, disconnects the in-flight HTTPS connection, shuts the executor, stops the foreground service. |
| Survives backgrounding | Foreground service keeps the process alive (recommended — §18 Decision A). |

---

## 10. Persistence (SharedPreferences)

Stored in a dedicated prefs file `TelegramBotPrefs` (kept separate from the existing `CoupenAppPrefs` so nothing collides):

| Key | Meaning |
|-----|---------|
| `bot_token` | Telegram bot token |
| `chat_id` | Target chat ID |
| `auto_start` | Auto-start-on-launch toggle |
| `last_update_offset` | Last processed `update_id` (prevents reprocessing) |
| `stat_total_received` / `stat_total_processed` | Running counters shown on the card |

> **Security note:** the token is a credential. It will be stored in private app storage (default `MODE_PRIVATE`). Optional hardening (EncryptedSharedPreferences) is listed in §18, Decision E.

---

## 11. Startup behaviour (auto-start)

On `MainActivity` launch, **after** the existing startup work:
```
if (TelegramConfig.autoStart && token and chatId are present):
      start the bot automatically (same path as pressing Start Bot)
else:
      remain stopped until the user presses Start Bot
```
This satisfies the optional "☐ Automatically Start Telegram Bot on App Launch" requirement.

---

## 12. Permissions & manifest changes

| Change | Why |
|--------|-----|
| `INTERNET` | Already present ✅ |
| `FOREGROUND_SERVICE` | Run the listener as a foreground service (if Decision A = service) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ requires a typed foreground service |
| `POST_NOTIFICATIONS` | Android 13+ needs runtime permission to show the ongoing notification |
| `<service android:name=".TelegramBotService" …/>` | Declare the service |

If we choose the lighter **Option B** (no service, §18 Decision A), only a notification permission may be needed and no service is declared.

---

## 13. Files to add / modify

**New files**
| File | Purpose |
|------|---------|
| `TelegramBotManager.java` | Core listener/controller singleton |
| `TelegramBotService.java` | Foreground service (Decision A) |
| `CouponExtractor.java` | Message → coupon code |
| `TelegramConfig.java` | Prefs read/write |
| `res/layout/…` bot-control views | Panel, status card, event log (added into `activity_main.xml` or an `<include>`) |

**Modified files (additive only)**
| File | Change |
|------|--------|
| `activity_main.xml` | Add the Bot Control panel UI |
| `MainActivity.java` | Wire buttons, observe manager state, deliver coupons to the **existing** `applyAndFireToAll`, handle auto-start |
| `AndroidManifest.xml` | Permissions + service declaration |

**Explicitly NOT touched:** `content.js`, `applyAndFireToAll`, `buildCoupenCommandJs`, per-card `Profile` isolation, the 24h session logic, `ManageUrlActivity`. The redemption core stays exactly as it is.

---

## 14. Security & privacy notes

- The **bot token grants full control of your Telegram bot** — treat it like a password. It stays in private app storage; optional encryption in Decision E.
- Incoming messages are **filtered by your Chat ID**, so a stranger who finds the bot cannot inject coupons.
- Only outbound HTTPS to `api.telegram.org`; no inbound ports, no server needed.

---

## 15. Edge cases handled

- Invalid/empty token or chat ID → blocked at Start with a clear ERROR message.
- Coupon arrives with **no cards open** → logged, not fired (nothing to fire into).
- Duplicate message / same code twice → ignored.
- App killed by OS while backgrounded → foreground service greatly reduces this; on relaunch, auto-start (if enabled) resumes and offset prevents reprocessing.
- Rapid burst of coupons → processed in order; each fired as it arrives.
- Airplane mode / flaky network → RECONNECTING with backoff, auto-recovers.

---

## 16. Testing plan

1. **Test Connection** with a valid and an invalid token → correct success/failure messages.
2. Start Bot → status 🟢, notification shown, log "Connected as @bot".
3. Send a coupon from the configured chat → input field updates, all open cards fire, counters increment, log entry with timestamp.
4. Send from a *different* chat → ignored.
5. Send the same code twice → second one ignored.
6. Toggle airplane mode mid-run → RECONNECTING → recovers to RUNNING.
7. Stop Bot → 🔴, notification cleared, no further polling (verify with logs).
8. Enable Auto-Start, relaunch app → bot starts on its own.
9. Regression: manual Apply + Fire still works exactly as before.

---

## 17. Implementation phases (once approved)

1. **Config + persistence** — fields, Save, `TelegramConfig`.
2. **Manager + Test Connection** — `getMe`, state enum, callbacks (no polling yet).
3. **Long-poll loop** — `getUpdates`, offset, backoff, Start/Stop.
4. **Coupon pipeline** — extractor + wire to `applyAndFireToAll`.
5. **Foreground service + notification** (Decision A).
6. **Status card + live log UI.**
7. **Auto-start on launch.**
8. **Test pass** per §16.

---

## 18. Decisions I need you to confirm before I build

| # | Decision | Options | My recommendation |
|---|----------|---------|-------------------|
| **A** | Background model | (1) **Foreground service** — survives backgrounding, shows a notification, most reliable. (2) In-activity thread — simpler, but the OS may kill it when the app is backgrounded. | **(1) Foreground service** — matches "long-running / continuous / auto-start." |
| **B** | Where the panel lives | (1) Collapsible panel inside `MainActivity`. (2) A separate "Bot Control" screen. | **(1) Inside MainActivity** — status stays visible next to the cards that fire. |
| **C** | **Coupon message format** | I need a real example of a coupon message from your Telegram source so the extractor is accurate. | Please paste 2–3 sample messages. Default = regex `[A-Za-z0-9]{4,20}`. |
| **D** | Reply back to Telegram? | Send a "✅ fired to N cards" confirmation to the chat, or stay silent. | Optional — **off** by default. |
| **E** | Encrypt the stored token? | Plain private prefs vs. EncryptedSharedPreferences. | Plain prefs is fine for most; encryption if you want extra safety. |

---

### Next step
Review this plan. Tell me your choices for **A–E** (especially **C — a few sample coupon messages**), and I'll implement it in the phases above **without changing any of your existing redemption code**.
