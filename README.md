# Gowri Screener

Personal Android APK: EOD overreaction screener (Home) + pre-run-up fundamental screener (Screener tab).

**Not an auto-trade signal.** Ranked dashboards only. Personal use.

## Tabs

| Tab | Purpose |
|-----|---------|
| **Home** | Idiosyncratic drop + news severity (Nifty 100) |
| **News** | Cached headlines |
| **Screener** | screener.in capture → Layer 1/2/3 pre-rating funnel (~613 stocks) |
| **Swing** | Momentum First + Sleeping Giant screens (NSE bhavcopy, free) |

## Download (latest debug APK)

**[Download APK v1.4.1](releases/bse-blueprint-screener-1.4.1-debug.apk)** — Swing detector fixes: real ranking, dormancy math, as-of/regime states.

Direct link (raw):

```
https://github.com/gowrii23/Cursor_Stock_News/raw/main/releases/bse-blueprint-screener-1.4.1-debug.apk
```

On your phone: download the APK, allow install from unknown sources if prompted, then open the file to install.

## Build (debug APK)

```bash
export ANDROID_HOME=$HOME/android-sdk   # or your SDK path
./gradlew :app:assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Screener tab (first run)

1. Open **Screener** tab → **Run Scan** (or toolbar **Run**)
2. Log in to screener.in inside the WebView if prompted (once)
3. Tap **Start Capture** — paginates through ~25 pages (~3–5 min)
4. Progress modal runs Layer 1 filters, Layer 2 scoring, Layer 3 technical overlay on shortlist
5. Review **80+ High** / **60–79 Watch** tiers; tap a stock for score breakdown + manual verification checklist

## Swing tab (first run)

1. Open **Swing** tab → **Run Swing Screen** (or toolbar **Run**)
2. App syncs NSE bhavcopy for Nifty 100 (free, no API key)
3. Checks **market regime** (Nifty vs 200 SMA) and runs:
   - **Momentum First** — only when regime is bullish
   - **Sleeping Giant** — dormant base breakout (price/volume only)
4. Filter by screen type; tap a hit for signal breakdown

## Home tab (first run)

## Project layout

```
app/src/main/java/.../ui/      # Activities
app/src/main/java/.../work/    # WorkManager
app/src/main/python/           # Screener pipeline
app/src/main/assets/           # Universe + Blueprint tags + defaults
```

## Notes

- Universe list is static JSON (refresh quarterly)
- Severity keywords are a living config — expect iteration
- Chaquopy free tier is intended for open-source / evaluation; review [Chaquopy licensing](https://chaquo.com/chaquopy/) before redistribution
