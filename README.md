# BSE Blueprint Screener

Personal Android APK: EOD news-driven overreaction screener for Nifty 50 + Next 50.

**Not an auto-trade signal.** Ranked, tagged dashboard only. Personal use.

## What it does

Flags stocks where:

1. Price fell idiosyncratically vs beta-implied index move (`z < -1.5` by default)
2. Matching news exists (NSE bhavcopy prices + Pulse / NSE announcements / Google News for flags)
3. Severity keyword filter tags `CANDIDATE` / `EXCLUDE` / `UNKNOWN`
4. Optional BSE Blueprint theme tags apply
5. Composite conviction score ranks the watchlist

## Stack

- Kotlin UI (Dashboard, Stock Detail + MPAndroidChart, News Feed, Settings)
- Python logic via [Chaquopy](https://chaquo.com/chaquopy/) (`pandas`, `numpy`, NSE bhavcopy CSV, Pulse/NSE/Google News RSS)
- WorkManager daily job (Wi‑Fi / charging constraints configurable)
- On-device SQLite history

## Download (latest debug APK)

**[Download APK v1.1.0](releases/bse-blueprint-screener-1.1.0-tier-a-debug.apk)** — Tier A data layer (NSE bhavcopy prices, Pulse + NSE + Google News, partial live mode).

Direct link (raw):

```
https://github.com/gowrii23/Cursor_Stock_News/raw/main/releases/bse-blueprint-screener-1.1.0-tier-a-debug.apk
```

Previous build: [v1.0.0 Tier 1+2](releases/bse-blueprint-screener-1.0.0-tier1-2-debug.apk)

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

## First launch

- Seeds a **demo** watchlist offline so UI works immediately
- Pull-to-refresh / menu **Run screen** fetches NSE bhavcopy (first run backfills ~280 trading days; allow 3–5 min on Wi‑Fi)
- Live news: Zerodha Pulse + NSE announcements + Google News for flagged stocks only
- Tune keywords, z-threshold, and Blueprint JSON in **Settings**

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
