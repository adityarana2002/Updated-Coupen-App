# CoupenApp Flow Diagram

This document outlines the architecture and data flow of the CoupenApp application.

## High-Level Overview

The app manages multiple isolated web sessions (`GeckoView`) and automates coupon code application using a URL-parameter-based communication strategy.

```mermaid
graph TD
    User((User))
    
    subgraph Android_App [Android Application]
        UI[MainActivity UI]
        Logic[MainActivity Logic]
        SessionMgr[GeckoSession Manager]
        Storage[SharedPreferences / LoginStorage]
    end

    subgraph Browser_Engine [GeckoView Engine]
        Session1[GeckoSession 1]
        Session2[GeckoSession 2]
        SessionN[GeckoSession N...]
        Ext[WebExtension (CoupenHelper)]
    end

    subgraph Web_Page [Target Website]
        PageContent[DOM / Page Content]
        ContentScript[content.js]
    end

    %% User Actions
    User -->|1. Select Link & Num Pages| UI
    User -->|2. Enter Gift Code| UI
    User -->|3. Click Apply / Fire| UI

    %% App Logic
    UI --> Logic
    Logic -->|Load/Save Config| Storage
    Logic -->|Create N Sessions| SessionMgr
    SessionMgr -->|Initialize| Session1
    SessionMgr -->|Initialize| Session2
    SessionMgr -->|Initialize| SessionN

    %% Extension Injection
    Logic -->|Install Extension| Ext
    Ext -.->|Injects| ContentScript

    %% Action: Apply/Fire
    Logic -- "Relies on URL Params" --> SessionMgr
    SessionMgr -- "session.loadUri(url + '?gc=CODE&fire=true')" --> Session1
    SessionMgr -- "Reloads with Params" --> Session2

    %% Content Script Logic
    Session1 --> PageContent
    ContentScript -- "Reads window.location.search" --> PageContent
    
    subgraph Automation_Flow [Automation Logic (content.js)]
        CheckParams{Check URL Params}
        FindInput[Find Gift Code Input]
        FillInput[Fill Input Value]
        CheckFire{Fire=True?}
        ClickRedeem[Click Redeem Button]
    end

    PageContent --> CheckParams
    CheckParams -->|Found 'gc'| FindInput
    FindInput -->|Input Found| FillInput
    FillInput --> CheckFire
    CheckFire -->|Yes| ClickRedeem
```

## Detailed Flow Description

1.  **Initialization**:
    *   The app initializes `GeckoRuntime` and installs the local WebExtension (`assets/messaging/`).
    *   Links are loaded from `SharedPreferences`.

2.  **Session Creation**:
    *   User selects a link and a number of pages.
    *   `MainActivity` creates multiple isolated `GeckoSession` instances.
    *   Each session loads the target URL.
    *   **Login Handling**: If a login occurs, the app saves the credential and automatically redirects to the target page after 3 seconds.

3.  **Command Dispatch (URL-Based)**:
    *   Instead of complex messaging, the app controls the page by reloading it with specific URL parameters.
    *   **Apply Code**: Appends `?gc=YOUR_CODE` to the current URL and reloads all sessions.
    *   **Fire (Redeem)**: Appends `?gc=YOUR_CODE&fire=true` and reloads.

4.  **Execution (Content Script)**:
    *   The injected `content.js` runs on every page load.
    *   It parses the URL query parameters.
    *   If `gc` is present, it scans the DOM for a suitable input field (using smart selectors and heuristics).
    *   If found, it fills the value and dispatches input events.
    *   If `fire=true` is present, it searches for a "Redeem" or "Submit" button and clicks it.
