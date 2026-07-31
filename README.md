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

**[Download APK v1.2.0](releases/bse-blueprint-screener-1.2.0-debug.apk)** — Run progress modal with live log feed + Tier A data layer.

Direct link (raw):

```
https://github.com/gowrii23/Cursor_Stock_News/raw/main/releases/bse-blueprint-screener-1.2.0-debug.apk
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

## First launch

- Seeds a **demo** watchlist offline so UI works immediately
- Tap **Run** in the toolbar to open the progress modal (live log + progress bar)
- First run backfills ~280 trading days on Wi‑Fi (~3–8 min); progress is shown in the modal
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
