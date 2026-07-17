# Indoor Phone Tracker

Estimates a phone's location inside a house using **only existing WiFi routers** —
no ESP32, no BLE beacons, no UWB, no GPS, no extra hardware.

## How it actually works (read this first)

Two things people often assume about WiFi indoor positioning turn out to be false,
and the design below is built around the real constraints instead:

1. **Browsers cannot read WiFi RSSI.** This is blocked by every mobile browser for
   privacy reasons — no workaround, no exception. That's why this project has an
   Android companion app: it's not optional polish, it's the only way to get real
   signal numbers off the phone at all.

2. **Pure trilateration (distance-from-RSSI math) is unreliable indoors.** RSSI-to-distance
   conversion assumes signal loss is a clean function of distance, but walls, furniture,
   your own body, and multipath reflection distort it heavily. Professional indoor
   positioning tools (Ekahau, Cisco DNA Spaces, etc.) primarily use **fingerprinting**
   instead: you walk around once, record what the WiFi signal actually looks like at
   known spots ("fingerprints"), and then match live readings against that recorded
   map. This project uses **fingerprinting as the primary method**, with trilateration
   available as a supplementary signal, and a Kalman filter to smooth the result over
   time so the dot doesn't jitter.

## Components

```
indoor-phone-tracker/
├── android-app/        Kotlin app — scans WiFi, uploads readings to Firestore
└── web-dashboard/       Single HTML file — floor plan editor, calibration, live view
```

## Android permissions required, and why

| Permission | Why it's needed |
|---|---|
| `ACCESS_WIFI_STATE` | Read WiFi scan results (signal strength per router) |
| `CHANGE_WIFI_STATE` | Trigger a new scan (`startScan()`) |
| `ACCESS_FINE_LOCATION` | Android requires this to release WiFi scan results, on the theory that nearby WiFi networks can reveal your location. This is an OS-level rule, not something this app is choosing to do — even a completely offline app must request it to get scan results on Android 8–12. |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Replaces the location-permission requirement for WiFi scanning on newer Android versions. Request this on API 33+; fall back to `ACCESS_FINE_LOCATION` below that. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` | Needed to keep scanning while the app runs in the background, with a persistent notification (Android requires this to be visible to the user — you can't silently scan in the background without them knowing). |
| Internet (implicit) | To upload readings to Firestore. |

**A real constraint you should know:** Android throttles active WiFi scan requests
to ~4 per 2-minute window per app (since Android 9, to save battery/reduce radio
congestion across all apps on the device). This app respects that — it will not
promise scans faster than the OS allows. In practice this means the position
updates roughly every 15–30 seconds, not multiple times per second. This is a
platform limit, not a bug in this app.

## Setup overview

1. **Create a free Firebase project** → enable **Firestore Database** (start in test
   mode for personal use). This is the shared store between your phone and the dashboard.
2. **Open `android-app/` in Android Studio**, drop in your `google-services.json`
   (from Firebase console → Project settings → your Android app), build, install on
   your phone.
3. **Open `web-dashboard/index.html`** in any browser, paste the same Firebase web
   config, and:
   - Upload/draw your floor plan.
   - Walk to several known spots in the house; on the Android app tap "Record
     fingerprint here" matched to where you tap on the dashboard's floor plan.
   - Once you have ~10-20 fingerprints spread around the space, switch to Live
     Tracking — the dot estimates position by comparing live signal to your
     recorded fingerprints.
4. Everything after setup runs on your **local network / your own Firebase
   project** — no third party sees your data beyond Google's Firebase
   infrastructure, which you control.

## Multi-router support

Fingerprinting handles multiple routers naturally: each fingerprint is a full
vector of *all* visible networks' signal strengths (BSSID → RSSI), not just one.
More routers visible = more dimensions to match on = better accuracy. Two is a
usable minimum; three or more meaningfully improves it.
