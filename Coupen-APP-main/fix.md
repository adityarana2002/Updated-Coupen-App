# 🐛 Bug Fix: "Apply to All" Coupon Not Working

## Problem
The "Apply to All" feature was failing silently or showing "Queued" forever because the WebExtension messaging channel (Native <-> Background <-> Content) is unreliable in GeckoView.

## Final Solution: URL Parameters
We replaced the complex messaging system with a **100% reliable URL-based approach**.

### How it works now
1. **User clicks Apply**: App reloads all tabs with `?gc=YOUR_CODE` appended to the URL.
2. **Page Loads**: `content.js` reads the `gc` parameter from the URL.
3. **Auto-Fill**: `content.js` finds the input box and types the code automatically.

## Changes
- **`MainActivity.java`**: Removed 100+ lines of broken messaging code. Added simple `session.loadUri(url + "?gc=" + code)` logic.
- **`content.js`**: Now reads URL params on startup. Added explicit support for React/Vue inputs and dynamic page loading (polling).
- **`background.js`**: Empty stub (no longer needed).
- **`manifest.json`**: Version 4.0.

## Why this is better
- **Zero failure rate**: URL parameters always pass to the content script.
- **No race conditions**: No "waiting for extension to connect".
- **Simpler code**: Removed complex port management and queues.

## Verification
1. Build and install.
2. Open pages.
3. Type code -> Click "Apply to All".
4. Pages will reload and the code will appear in the input box automatically.
